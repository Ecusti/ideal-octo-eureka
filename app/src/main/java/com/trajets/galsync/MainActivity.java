package com.trajets.galsync;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.trajets.galsync.auth.AuthManager;
import com.trajets.galsync.settings.SettingsActivity;
import com.trajets.galsync.settings.SettingsManager;
import com.trajets.galsync.sync.ContactSyncManager;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GalSync";
    private static final int PERMISSIONS_REQUEST_CODE = 100;
    private static final int SETTINGS_REQUEST_CODE = 200;

    private AuthManager authManager;
    private ContactSyncManager syncManager;
    private SettingsManager settingsManager;

    private Button btnLogin;
    private Button btnSync;
    private Button btnLogout;
    private Button btnSettings;
    private TextView tvStatus;
    private ProgressBar progressBar;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== onCreate START ===");
        setContentView(R.layout.activity_main);
        Log.d(TAG, "Layout chargé");

        // Créer le compte Android si nécessaire
        createAccountIfNeeded();

        // Programmer la synchronisation automatique quotidienne
        scheduleAutoSync();

        settingsManager = new SettingsManager(this);

        initializeViews();
        initializeManagers();

        // Vérifier si un utilisateur est déjà connecté
        if (settingsManager.isConfigured()) {
            checkExistingAccount();
        }

        checkPermissions();

        new android.os.Handler().postDelayed(new Runnable() {
            public void run() {
                updateUI();
                Log.d(TAG, "UI activée après délai");
            }
        }, 2000);

        Log.d(TAG, "=== onCreate END ===");
    }

    private void checkExistingAccount() {
        Log.d(TAG, "Vérification d'un compte existant...");
        authManager.checkSignedInAccount(new AuthManager.AuthCallback() {
            public void onSuccess(String accessToken) {
                Log.d(TAG, "Compte existant trouvé et token récupéré");
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateUI();
                        tvStatus.setText("Connecté - Prêt à synchroniser");
                    }
                });
            }

            public void onError(Exception exception) {
                Log.d(TAG, "Aucun compte existant ou erreur: " + exception.getMessage());
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateUI();
                    }
                });
            }
        });
    }
    private void initializeViews() {
        Log.d(TAG, "=== initializeViews START ===");

        btnLogin = findViewById(R.id.btn_login);
        btnSync = findViewById(R.id.btn_sync);
        btnLogout = findViewById(R.id.btn_logout);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);

        Log.d(TAG, "btnLogin is null? " + (btnLogin == null));

        if (btnLogin != null) {
            btnLogin.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Log.d(TAG, "===== BOUTON LOGIN CLIQUÉ =====");
                    performLogin();
                }
            });
            Log.d(TAG, "Listener ajouté au bouton login");
        } else {
            Log.e(TAG, "ERREUR: btnLogin est NULL!");
        }

        btnSync.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG, "===== BOUTON SYNC CLIQUÉ =====");
                performSync();
            }
        });

        btnLogout.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG, "===== BOUTON LOGOUT CLIQUÉ =====");
                performLogout();
            }
        });

        btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivityForResult(intent, SETTINGS_REQUEST_CODE);
            }
        });

        Log.d(TAG, "=== initializeViews END ===");
    }

    private void initializeManagers() {
        Log.d(TAG, "Initialisation des managers...");
        authManager = new AuthManager(this);
        syncManager = new ContactSyncManager(this);
        Log.d(TAG, "Managers initialisés");
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.GET_ACCOUNTS
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE);
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Permissions requises pour synchroniser les contacts",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void performLogin() {
        Log.d(TAG, "performLogin appelé");
        if (!settingsManager.isConfigured()) {
            Toast.makeText(this, R.string.settings_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        setLoading(true);
        tvStatus.setText("Connexion en cours...");

        Log.d(TAG, "Appel de authManager.signIn");
        authManager.signIn(new AuthManager.AuthCallback() {
            public void onSuccess(String accessToken) {
                Log.d(TAG, "Login réussi!");
                runOnUiThread(new Runnable() {
                    public void run() {
                        setLoading(false);
                        tvStatus.setText("Connecté avec succès");
                        updateUI();
                        Toast.makeText(MainActivity.this, "Authentification réussie",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            public void onError(Exception exception) {
                Log.e(TAG, "Erreur de login", exception);
                runOnUiThread(new Runnable() {
                    public void run() {
                        setLoading(false);
                        tvStatus.setText("Erreur de connexion");
                        updateUI();
                        Toast.makeText(MainActivity.this,
                                "Erreur: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void performSync() {
        Log.d(TAG, "performSync appelé");
        setLoading(true);
        tvStatus.setText("Synchronisation des contacts...");

        syncManager.syncContacts(new ContactSyncManager.SyncCallback() {
            public void onProgress(final int current, final int total) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        tvStatus.setText("Synchronisation: " + current + "/" + total + " contacts");
                    }
                });
            }

            public void onComplete(final int syncedCount) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        setLoading(false);
                        tvStatus.setText("Synchronisation terminée: " + syncedCount + " contacts");
                        Toast.makeText(MainActivity.this,
                                syncedCount + " contacts synchronisés",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            public void onError(Exception exception) {
                Log.e(TAG, "Erreur sync", exception);
                runOnUiThread(new Runnable() {
                    public void run() {
                        setLoading(false);
                        tvStatus.setText("Erreur de synchronisation");
                        Toast.makeText(MainActivity.this,
                                "Erreur: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void performLogout() {
        Log.d(TAG, "performLogout appelé");
        authManager.signOut(new Runnable() {
            public void run() {
                tvStatus.setText("Déconnecté");
                updateUI();
                Toast.makeText(MainActivity.this, "Déconnexion réussie",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK) {
            // Settings changed, reinitialize managers
            initializeManagers();
            if (settingsManager.isConfigured()) {
                checkExistingAccount();
            }
            updateUI();
        }
    }

    private void updateUI() {
        boolean configured = settingsManager.isConfigured();
        boolean isSignedIn = configured && authManager.isSignedIn();

        btnLogin.setEnabled(configured && !isSignedIn);
        btnSync.setEnabled(isSignedIn);
        btnLogout.setEnabled(isSignedIn);

        if (!configured) {
            tvStatus.setText(R.string.settings_not_configured);
        } else if (isSignedIn) {
            tvStatus.setText("Connecté - Prêt à synchroniser");
        } else {
            tvStatus.setText("Non connecté");
        }
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnSync.setEnabled(!loading && authManager.isSignedIn());
        btnLogout.setEnabled(!loading && authManager.isSignedIn());
    }

    private void createAccountIfNeeded() {
        android.accounts.AccountManager accountManager = android.accounts.AccountManager.get(this);
        android.accounts.Account account = new android.accounts.Account(
                "Fondation Trajets",
                "com.trajets.galsync"
        );

        // Vérifier si le compte existe déjà
        android.accounts.Account[] existingAccounts = accountManager.getAccountsByType("com.trajets.galsync");

        if (existingAccounts.length == 0) {
            // Créer le compte
            boolean accountCreated = accountManager.addAccountExplicitly(account, null, null);
            if (accountCreated) {
                Log.d(TAG, "Compte 'Fondation Trajets' créé avec succès");
                Toast.makeText(this, "Compte Fondation Trajets créé", Toast.LENGTH_SHORT).show();

                // Activer la synchronisation
                android.content.ContentResolver.setIsSyncable(account, "com.android.contacts", 1);
                android.content.ContentResolver.setSyncAutomatically(account, "com.android.contacts", false);
            } else {
                Log.e(TAG, "Échec de la création du compte");
            }
        } else {
            Log.d(TAG, "Compte 'Fondation Trajets' existe déjà");
        }
    }

    private void scheduleAutoSync() {
        androidx.work.PeriodicWorkRequest syncWorkRequest =
                new androidx.work.PeriodicWorkRequest.Builder(
                        com.trajets.galsync.sync.SyncWorker.class,
                        1, java.util.concurrent.TimeUnit.DAYS
                )
                        .addTag("auto_sync")
                        .setConstraints(
                                new androidx.work.Constraints.Builder()
                                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                        .build()
                        )
                        .build();

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "contact_sync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
        );

        Log.d(TAG, "Synchronisation automatique quotidienne programmée");
    }
}