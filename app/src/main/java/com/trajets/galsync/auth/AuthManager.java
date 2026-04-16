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
import com.microsoft.identity.client.exception.MsalServiceException;
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

    /**
     * Auth strategies to try, in order, depending on the profile type.
     * <p>
     * Normal device: BROKER → BROWSER → DEFAULT_NO_BROKER
     * Work profile:  BROKER → BROWSER → DEFAULT_NO_BROKER
     *   (broker is required for Strong MFA / device compliance Conditional Access
     *    policies — error 53003 occurs without it. Since both GalSync and
     *    Authenticator are in the same work profile, broker should work now
     *    that <queries> enables package visibility.)
     */
    private static final int[] STRATEGIES_NORMAL = {
            SettingsManager.AUTH_STRATEGY_BROKER,
            SettingsManager.AUTH_STRATEGY_BROWSER,
            SettingsManager.AUTH_STRATEGY_DEFAULT_NO_BROKER
    };
    private static final int[] STRATEGIES_WORK_PROFILE = {
            SettingsManager.AUTH_STRATEGY_BROKER,
            SettingsManager.AUTH_STRATEGY_BROWSER,
            SettingsManager.AUTH_STRATEGY_DEFAULT_NO_BROKER
    };

    private static final String[] STRATEGY_NAMES = {"BROKER", "BROWSER", "DEFAULT_NO_BROKER"};

    private final Context context;
    private Activity activity;
    private ISingleAccountPublicClientApplication msalApp;
    private String accessToken;
    private String msalInitError;
    private boolean isInitializing = false;
    private boolean isWorkProfile = false;

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

        isWorkProfile = SettingsManager.isRunningInWorkProfile(context);
        int[] strategies = isWorkProfile ? STRATEGIES_WORK_PROFILE : STRATEGIES_NORMAL;

        Log.d(TAG, "Initialisation MSAL — profil de travail: " + isWorkProfile
                + ", stratégies: " + strategies.length);

        // Log available browsers for diagnostics
        logAvailableBrowsers();

        initializeMsalWithStrategy(settingsManager, strategies, 0);
    }

    /**
     * Try MSAL init with strategy at the given index. On failure, automatically
     * advances to the next strategy.
     */
    private void initializeMsalWithStrategy(SettingsManager settingsManager,
                                             int[] strategies, int index) {
        if (index >= strategies.length) {
            isInitializing = false;
            Log.e(TAG, "MSAL init échoué — toutes les stratégies ont échoué: " + msalInitError);
            return;
        }

        int strategy = strategies[index];
        String strategyName = strategy < STRATEGY_NAMES.length
                ? STRATEGY_NAMES[strategy] : String.valueOf(strategy);
        Log.d(TAG, "Tentative MSAL stratégie " + (index + 1) + "/" + strategies.length
                + ": " + strategyName);

        File configFile = settingsManager.generateAuthConfig(strategy);
        if (configFile == null || !configFile.exists()) {
            Log.w(TAG, "Config generation failed for strategy " + strategyName);
            msalInitError = "Impossible de générer le fichier de configuration MSAL";
            initializeMsalWithStrategy(settingsManager, strategies, index + 1);
            return;
        }

        // Log the generated config for debugging
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(configFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            Log.d(TAG, "MSAL config (" + strategyName + "):\n" + sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "Could not log config file");
        }

        try {
            PublicClientApplication.createSingleAccountPublicClientApplication(
                    context, configFile,
                    new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                        public void onCreated(ISingleAccountPublicClientApplication application) {
                            msalApp = application;
                            isInitializing = false;
                            msalInitError = null;
                            Log.d(TAG, "MSAL initialisé avec succès (stratégie: " + strategyName + ")");
                        }

                        public void onError(MsalException exception) {
                            Log.e(TAG, "MSAL init onError (" + strategyName + "): "
                                    + exception.getClass().getSimpleName()
                                    + " — " + exception.getMessage(), exception);
                            msalInitError = strategyName + ": " + exception.getMessage();
                            initializeMsalWithStrategy(settingsManager, strategies, index + 1);
                        }
                    });
        } catch (Exception e) {
            // createSingleAccountPublicClientApplication can throw synchronously
            // on some devices / work profile configurations
            Log.e(TAG, "MSAL init exception (" + strategyName + "): "
                    + e.getClass().getSimpleName() + " — " + e.getMessage(), e);
            msalInitError = strategyName + ": " + e.getMessage();
            initializeMsalWithStrategy(settingsManager, strategies, index + 1);
        }
    }

    /**
     * Log which browsers are visible to help diagnose work profile issues.
     */
    private void logAvailableBrowsers() {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.Intent browserIntent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://login.microsoftonline.com"));
            java.util.List<android.content.pm.ResolveInfo> browsers =
                    pm.queryIntentActivities(browserIntent, 0);
            StringBuilder sb = new StringBuilder("Navigateurs disponibles: ");
            for (android.content.pm.ResolveInfo info : browsers) {
                if (info.activityInfo != null) {
                    sb.append(info.activityInfo.packageName).append(", ");
                }
            }
            Log.d(TAG, sb.toString());

            // Check specific browsers
            String[] knownBrowsers = {
                    "com.microsoft.emmx",       // Edge
                    "com.android.chrome",        // Chrome
                    "com.sec.android.app.sbrowser" // Samsung Internet
            };
            for (String pkg : knownBrowsers) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    Log.d(TAG, "Navigateur installé: " + pkg);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    Log.d(TAG, "Navigateur absent: " + pkg);
                }
            }

            // Check broker apps
            String[] brokerApps = {
                    "com.azure.authenticator",
                    "com.microsoft.windowsintune.companyportal"
            };
            for (String pkg : brokerApps) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    Log.d(TAG, "Broker installé: " + pkg);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    Log.d(TAG, "Broker absent: " + pkg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate browsers", e);
        }
    }

    public void checkSignedInAccount(final AuthCallback callback) {
        if (msalApp == null) {
            if (isInitializing) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> checkSignedInAccount(callback), 500);
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
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> signIn(callback), 500);
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
                        IAccount account = authenticationResult.getAccount();
                        Log.d(TAG, "Connexion réussie — compte: "
                                + (account != null ? account.getUsername() : "inconnu")
                                + ", authority: " + (account != null ? account.getAuthority() : "n/a"));
                        callback.onSuccess(accessToken);
                    }

                    public void onError(MsalException exception) {
                        Log.e(TAG, "Erreur de connexion: " + exception.getClass().getSimpleName()
                                + " — " + exception.getMessage(), exception);
                        logMsalErrorDetails(exception);
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
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> acquireTokenSilently(callback), 500);
                return;
            }
            callback.onError(new Exception("MSAL non initialisé"));
            return;
        }

        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            public void onAccountLoaded(IAccount activeAccount) {
                if (activeAccount != null) {
                    AcquireTokenSilentParameters silentParams = new AcquireTokenSilentParameters.Builder()
                            .withScopes(Arrays.asList(SCOPES))
                            .forAccount(activeAccount)
                            .fromAuthority(activeAccount.getAuthority())
                            .withCallback(new SilentAuthenticationCallback() {
                                public void onSuccess(IAuthenticationResult authenticationResult) {
                                    accessToken = authenticationResult.getAccessToken();
                                    IAccount account = authenticationResult.getAccount();
                                    Log.d(TAG, "Token silencieux obtenu — compte: "
                                            + (account != null ? account.getUsername() : "inconnu"));
                                    callback.onSuccess(accessToken);
                                }

                                public void onError(MsalException exception) {
                                    if (exception instanceof MsalUiRequiredException && activity != null) {
                                        Log.d(TAG, "Token silencieux refusé, basculement vers connexion interactive");
                                        logMsalErrorDetails(exception);
                                        performInteractiveSignIn(callback);
                                    } else {
                                        Log.e(TAG, "Erreur token silencieux: "
                                                + exception.getClass().getSimpleName()
                                                + " — " + exception.getMessage());
                                        logMsalErrorDetails(exception);
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

    /**
     * Log detailed error info from MsalServiceException (error code, HTTP status, claims).
     * Helps diagnose Conditional Access (53003), device compliance, and broker issues.
     */
    private void logMsalErrorDetails(MsalException exception) {
        if (exception instanceof MsalServiceException) {
            MsalServiceException svcEx = (MsalServiceException) exception;
            Log.e(TAG, "  MsalServiceException — errorCode=" + svcEx.getErrorCode()
                    + ", httpStatus=" + svcEx.getHttpStatusCode());
        }
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
