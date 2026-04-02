package com.trajets.galsync.auth;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.microsoft.identity.client.AcquireTokenSilentParameters;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.SignInParameters;
import com.microsoft.identity.client.SilentAuthenticationCallback;
import com.microsoft.identity.client.exception.MsalException;
import com.microsoft.identity.client.exception.MsalUiRequiredException;
import com.trajets.galsync.settings.SettingsManager;

import java.io.File;
import java.util.Arrays;

public class AuthManager {
    private static final String TAG = "AuthManager";
    private static final String[] SCOPES = {
            "User.Read",
            "User.Read.All",
            "GroupMember.Read.All"
    };

    private final Context context;
    private Activity activity;
    private ISingleAccountPublicClientApplication msalApp;
    private String accessToken;
    private String msalInitError;
    private boolean isInitializing = false;

    public interface AuthCallback {
        void onSuccess(String accessToken);
        void onError(Exception exception);
    }

    /**
     * Constructor for background use (SyncWorker). Only silent token acquisition will work.
     */
    public AuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.activity = null;
        initializeMsal();
    }

    /**
     * Constructor for interactive use (Activity). Supports both interactive and silent auth.
     */
    public AuthManager(Activity activity) {
        this.context = activity;
        this.activity = activity;
        initializeMsal();
    }

    private void initializeMsal() {
        if (isInitializing) {
            Log.d(TAG, "MSAL déjà en cours d'initialisation");
            return;
        }

        SettingsManager settingsManager = new SettingsManager(context);

        if (!settingsManager.isConfigured()) {
            Log.d(TAG, "Settings non configurés — MSAL non initialisé");
            msalInitError = "Paramètres Entra ID non configurés";
            return;
        }

        isInitializing = true;
        msalInitError = null;

        // Try with broker first (supports phishing-resistant MFA via Authenticator).
        // If broker init fails (e.g. work profile isolation), automatically
        // retry with browser-only mode.
        initializeMsalWithConfig(settingsManager, true);
    }

    private void initializeMsalWithConfig(SettingsManager settingsManager, boolean withBroker) {
        Log.d(TAG, "Initialisation MSAL (broker=" + withBroker + ")...");

        File configFile = settingsManager.generateAuthConfig(withBroker);
        if (configFile == null || !configFile.exists()) {
            if (withBroker) {
                Log.w(TAG, "Config broker échouée, tentative sans broker...");
                initializeMsalWithConfig(settingsManager, false);
                return;
            }
            isInitializing = false;
            msalInitError = "Impossible de générer le fichier de configuration MSAL";
            Log.e(TAG, msalInitError);
            return;
        }

        PublicClientApplication.createSingleAccountPublicClientApplication(
                context, configFile,
                new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                    public void onCreated(ISingleAccountPublicClientApplication application) {
                        msalApp = application;
                        isInitializing = false;
                        msalInitError = null;
                        Log.d(TAG, "MSAL initialisé avec succès (broker=" + withBroker + ")");
                    }

                    public void onError(MsalException exception) {
                        if (withBroker) {
                            // Broker init failed (work profile, Authenticator not reachable, etc.)
                            // Retry without broker — use browser auth instead
                            Log.w(TAG, "MSAL broker init échoué: " + exception.getMessage()
                                    + " — tentative sans broker...");
                            initializeMsalWithConfig(settingsManager, false);
                        } else {
                            // Both broker and non-broker failed
                            isInitializing = false;
                            msalInitError = exception.getMessage();
                            Log.e(TAG, "MSAL init échoué (toutes tentatives): " + msalInitError, exception);
                        }
                    }
                });
    }

    public void checkSignedInAccount(final AuthCallback callback) {
        if (msalApp == null) {
            if (isInitializing) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        checkSignedInAccount(callback);
                    }
                }, 500);
                return;
            }
            callback.onError(new Exception("MSAL non initialisé"));
            return;
        }

        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    Log.d(TAG, "Compte déjà connecté: " + activeAccount.getUsername());
                    acquireTokenSilently(callback);
                } else {
                    Log.d(TAG, "Aucun compte connecté");
                    callback.onError(new Exception("Aucun compte"));
                }
            }

            public void onAccountChanged(IAccount priorAccount, IAccount currentAccount) {
            }

            public void onError(MsalException exception) {
                Log.e(TAG, "Erreur lors de la vérification du compte", exception);
                callback.onError(exception);
            }
        });
    }

    public void signIn(final AuthCallback callback) {
        Log.d(TAG, "signIn appelé. msalApp == null? " + (msalApp == null));

        if (activity == null) {
            callback.onError(new Exception("Connexion interactive impossible sans Activity"));
            return;
        }

        if (msalApp == null) {
            if (isInitializing) {
                Log.d(TAG, "MSAL en cours d'initialisation, attente...");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        signIn(callback);
                    }
                }, 500);
                return;
            } else {
                String detail = msalInitError != null ? msalInitError : "vérifiez auth_config.json";
                Log.e(TAG, "MSAL non initialisé: " + detail);
                callback.onError(new Exception("MSAL non initialisé: " + detail));
                return;
            }
        }

        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    Log.d(TAG, "Compte déjà connecté, acquisition de token silencieuse");
                    acquireTokenSilently(callback);
                } else {
                    performInteractiveSignIn(callback);
                }
            }

            public void onAccountChanged(IAccount priorAccount, IAccount currentAccount) {
            }

            public void onError(MsalException exception) {
                Log.e(TAG, "Erreur lors de la vérification du compte", exception);
                performInteractiveSignIn(callback);
            }
        });
    }

    private void performInteractiveSignIn(final AuthCallback callback) {
        if (activity == null) {
            callback.onError(new Exception("Connexion interactive impossible sans Activity"));
            return;
        }

        Log.d(TAG, "Lancement de la connexion MSAL interactive...");

        SignInParameters signInParams = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(Arrays.asList(SCOPES))
                .withCallback(new AuthenticationCallback() {
                    public void onSuccess(IAuthenticationResult authenticationResult) {
                        accessToken = authenticationResult.getAccessToken();
                        Log.d(TAG, "Connexion réussie");
                        callback.onSuccess(accessToken);
                    }

                    public void onError(MsalException exception) {
                        Log.e(TAG, "Erreur de connexion", exception);
                        callback.onError(exception);
                    }

                    public void onCancel() {
                        Log.d(TAG, "Connexion annulée par l'utilisateur");
                        callback.onError(new Exception("Connexion annulée par utilisateur"));
                    }
                })
                .build();

        msalApp.signIn(signInParams);
    }

    public void acquireTokenSilently(final AuthCallback callback) {
        if (msalApp == null) {
            if (isInitializing) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        acquireTokenSilently(callback);
                    }
                }, 500);
                return;
            }
            callback.onError(new Exception("MSAL non initialisé"));
            return;
        }

        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    AcquireTokenSilentParameters silentParams = AcquireTokenSilentParameters.builder()
                            .withScopes(Arrays.asList(SCOPES))
                            .forAccount(activeAccount)
                            .fromAuthority(activeAccount.getAuthority())
                            .withCallback(new SilentAuthenticationCallback() {
                                public void onSuccess(IAuthenticationResult authenticationResult) {
                                    accessToken = authenticationResult.getAccessToken();
                                    callback.onSuccess(accessToken);
                                }

                                public void onError(MsalException exception) {
                                    if (exception instanceof MsalUiRequiredException && activity != null) {
                                        Log.d(TAG, "Token silencieux refusé (MFA/interaction requise), basculement vers connexion interactive");
                                        performInteractiveSignIn(callback);
                                    } else {
                                        callback.onError(exception);
                                    }
                                }
                            })
                            .build();

                    msalApp.acquireTokenSilentAsync(silentParams);
                } else {
                    callback.onError(new Exception("Aucun compte actif"));
                }
            }

            public void onAccountChanged(IAccount priorAccount, IAccount currentAccount) {
            }

            public void onError(MsalException exception) {
                callback.onError(exception);
            }
        });
    }

    public void signOut(final Runnable onComplete) {
        if (msalApp == null) {
            onComplete.run();
            return;
        }

        msalApp.signOut(new ISingleAccountPublicClientApplication.SignOutCallback() {
            public void onSignOut() {
                accessToken = null;
                Log.d(TAG, "Déconnexion réussie");
                onComplete.run();
            }

            public void onError(MsalException exception) {
                Log.e(TAG, "Erreur de déconnexion", exception);
                onComplete.run();
            }
        });
    }

    public boolean isSignedIn() {
        return accessToken != null && msalApp != null;
    }

    public String getAccessToken() {
        return accessToken;
    }
}
