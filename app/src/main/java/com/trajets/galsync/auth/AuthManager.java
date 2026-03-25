package com.trajets.galsync.auth;

import android.app.Activity;
import android.util.Log;

import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.SilentAuthenticationCallback;
import com.microsoft.identity.client.exception.MsalException;
import com.trajets.galsync.R;

public class AuthManager {
    private static final String TAG = "AuthManager";
    private static final String[] SCOPES = {"User.Read", "User.ReadBasic.All", "Contacts.Read"};

    private final Activity activity;
    private ISingleAccountPublicClientApplication msalApp;
    private String accessToken;
    private boolean isInitializing = false;

    public interface AuthCallback {
        void onSuccess(String accessToken);
        void onError(Exception exception);
    }

    public AuthManager(Activity activity) {
        this.activity = activity;
        initializeMsal();
    }

    private void initializeMsal() {
        if (isInitializing) {
            Log.d(TAG, "MSAL déjà en cours d'initialisation");
            return;
        }

        isInitializing = true;
        Log.d(TAG, "Démarrage de l'initialisation MSAL...");

        PublicClientApplication.createSingleAccountPublicClientApplication(
                activity,
                R.raw.auth_config,
                new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                    public void onCreated(ISingleAccountPublicClientApplication application) {
                        msalApp = application;
                        isInitializing = false;
                        Log.d(TAG, "✓ MSAL initialisé avec succès");
                    }

                    public void onError(MsalException exception) {
                        isInitializing = false;
                        Log.e(TAG, "✗ Erreur initialisation MSAL", exception);
                    }
                }
        );
    }

    public void checkSignedInAccount(final AuthCallback callback) {
        if (msalApp == null) {
            // Attendre que MSAL soit initialisé
            new android.os.Handler().postDelayed(new Runnable() {
                public void run() {
                    checkSignedInAccount(callback);
                }
            }, 500);
            return;
        }

        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    Log.d(TAG, "Compte déjà connecté: " + activeAccount.getUsername());
                    // Récupérer un token silencieusement
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

        if (msalApp == null) {
            if (isInitializing) {
                Log.d(TAG, "MSAL en cours d'initialisation, attente...");
                new android.os.Handler().postDelayed(new Runnable() {
                    public void run() {
                        signIn(callback);
                    }
                }, 500);
                return;
            } else {
                Log.e(TAG, "MSAL non initialisé et pas en cours d'initialisation");
                callback.onError(new Exception("MSAL non initialisé - vérifiez auth_config.json"));
                return;
            }
        }

        // Vérifier d'abord si un compte est déjà connecté
        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    // Un compte est déjà connecté, obtenir un token silencieusement
                    Log.d(TAG, "Compte déjà connecté, acquisition de token silencieuse");
                    acquireTokenSilently(callback);
                } else {
                    // Pas de compte, continuer avec la connexion interactive
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
        Log.d(TAG, "Lancement de la connexion MSAL interactive...");
        msalApp.signIn(activity, null, SCOPES, new AuthenticationCallback() {
            public void onSuccess(IAuthenticationResult authenticationResult) {
                accessToken = authenticationResult.getAccessToken();
                Log.d(TAG, "✓ Connexion réussie");
                callback.onSuccess(accessToken);
            }

            public void onError(MsalException exception) {
                Log.e(TAG, "✗ Erreur de connexion", exception);
                callback.onError(exception);
            }

            public void onCancel() {
                Log.d(TAG, "Connexion annulée par l'utilisateur");
                callback.onError(new Exception("Connexion annulée par utilisateur"));
            }
        });
    }

    public void acquireTokenSilently(final AuthCallback callback) {
        if (msalApp == null) {
            callback.onError(new Exception("MSAL non initialisé"));
            return;
        }

        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    msalApp.acquireTokenSilentAsync(SCOPES, activeAccount.getAuthority(),
                            new SilentAuthenticationCallback() {
                                public void onSuccess(IAuthenticationResult authenticationResult) {
                                    accessToken = authenticationResult.getAccessToken();
                                    callback.onSuccess(accessToken);
                                }

                                public void onError(MsalException exception) {
                                    callback.onError(exception);
                                }
                            });
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