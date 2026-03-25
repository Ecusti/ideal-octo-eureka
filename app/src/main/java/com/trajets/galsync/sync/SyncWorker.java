package com.trajets.galsync.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.trajets.galsync.settings.SettingsManager;

public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "=== Synchronisation automatique démarrée ===");

        try {
            Context context = getApplicationContext();
            SettingsManager settingsManager = new SettingsManager(context);

            if (!settingsManager.isConfigured()) {
                Log.d(TAG, "Entra ID non configuré, synchronisation ignorée");
                return Result.failure();
            }

            if (!settingsManager.isSyncEnabled()) {
                Log.d(TAG, "Synchronisation automatique désactivée");
                return Result.success();
            }

            // Use Context-based constructor (no Activity required for silent auth)
            ContactSyncManager syncManager = new ContactSyncManager(context);

            final boolean[] success = {false};
            final Object lock = new Object();

            syncManager.syncContacts(new ContactSyncManager.SyncCallback() {
                @Override
                public void onProgress(int current, int total) {
                    Log.d(TAG, "Progression: " + current + "/" + total);
                }

                @Override
                public void onComplete(int syncedCount) {
                    Log.d(TAG, "Synchronisation automatique terminée: " + syncedCount + " contacts");
                    synchronized (lock) {
                        success[0] = true;
                        lock.notify();
                    }
                }

                @Override
                public void onError(Exception exception) {
                    Log.e(TAG, "Erreur lors de la synchronisation automatique", exception);
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(300000); // Max 5 minutes
            }

            return success[0] ? Result.success() : Result.failure();

        } catch (Exception e) {
            Log.e(TAG, "Erreur critique dans SyncWorker", e);
            SettingsManager settingsManager = new SettingsManager(getApplicationContext());
            settingsManager.recordSyncError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return Result.failure();
        }
    }
}
