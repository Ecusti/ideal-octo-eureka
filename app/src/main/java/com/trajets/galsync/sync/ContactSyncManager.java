package com.trajets.galsync.sync;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import com.trajets.galsync.auth.AuthManager;
import com.trajets.galsync.models.EntraUser;
import com.trajets.galsync.settings.SettingsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContactSyncManager {
    private static final String TAG = "ContactSyncManager";
    private static final String ACCOUNT_TYPE = "com.trajets.galsync";
    private static final String ACCOUNT_NAME = "Fondation Trajets";

    private final Context context;
    private final AuthManager authManager;
    private final SettingsManager settingsManager;
    private final ExecutorService executor;

    public interface SyncCallback {
        void onProgress(int current, int total);
        void onComplete(int syncedCount);
        void onError(Exception exception);
    }

    /**
     * Constructor for use from an Activity (interactive auth available).
     */
    public ContactSyncManager(android.app.Activity activity) {
        this.context = activity;
        this.authManager = new AuthManager(activity);
        this.settingsManager = new SettingsManager(activity);
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Constructor for background use (SyncWorker). Only silent auth.
     */
    public ContactSyncManager(Context context) {
        this.context = context;
        this.authManager = new AuthManager(context);
        this.settingsManager = new SettingsManager(context);
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void syncContacts(final SyncCallback callback) {
        executor.execute(new Runnable() {
            public void run() {
                try {
                    final String[] tokenHolder = new String[1];
                    final Exception[] errorHolder = new Exception[1];
                    final Object lock = new Object();

                    synchronized (lock) {
                        authManager.acquireTokenSilently(new AuthManager.AuthCallback() {
                            public void onSuccess(String accessToken) {
                                synchronized (lock) {
                                    tokenHolder[0] = accessToken;
                                    lock.notify();
                                }
                            }

                            public void onError(Exception exception) {
                                synchronized (lock) {
                                    errorHolder[0] = exception;
                                    lock.notify();
                                }
                            }
                        });

                        try {
                            lock.wait(15000);
                        } catch (InterruptedException e) {
                            recordError(e);
                            callback.onError(e);
                            return;
                        }
                    }

                    if (errorHolder[0] != null) {
                        recordError(errorHolder[0]);
                        callback.onError(errorHolder[0]);
                        return;
                    }

                    if (tokenHolder[0] == null) {
                        Exception e = new Exception("Timeout lors de l'acquisition du token");
                        recordError(e);
                        callback.onError(e);
                        return;
                    }

                    List<EntraUser> users = fetchUsersFromGraph(tokenHolder[0]);

                    // Delete all existing GalSync contacts before re-inserting
                    deleteExistingContacts();

                    int syncedCount = syncContactsToDevice(users, callback);

                    settingsManager.recordSyncSuccess(syncedCount);
                    callback.onComplete(syncedCount);

                } catch (Exception e) {
                    Log.e(TAG, "Erreur de synchronisation", e);
                    recordError(e);
                    callback.onError(e);
                }
            }
        });
    }

    private void recordError(Exception e) {
        String message = e.getMessage();
        if (message == null) message = e.getClass().getSimpleName();
        settingsManager.recordSyncError(message);
    }

    /**
     * Delete all existing RawContacts belonging to our account.
     * This ensures changes in Entra ID (updated/removed users) are reflected.
     */
    private void deleteExistingContacts() {
        ContentResolver resolver = context.getContentResolver();
        int deleted = 0;

        Cursor cursor = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID},
                ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND " + ContactsContract.RawContacts.ACCOUNT_NAME + "=?",
                new String[]{ACCOUNT_TYPE, ACCOUNT_NAME},
                null
        );

        if (cursor != null) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            while (cursor.moveToNext()) {
                long rawContactId = cursor.getLong(0);
                ops.add(ContentProviderOperation.newDelete(
                        ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                                .appendPath(String.valueOf(rawContactId))
                                .build()
                ).build());

                // Apply in batches of 100 to avoid TransactionTooLargeException
                if (ops.size() >= 100) {
                    try {
                        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
                        deleted += ops.size();
                    } catch (Exception e) {
                        Log.e(TAG, "Erreur lors de la suppression d'un lot de contacts", e);
                    }
                    ops.clear();
                }
            }
            cursor.close();

            if (!ops.isEmpty()) {
                try {
                    resolver.applyBatch(ContactsContract.AUTHORITY, ops);
                    deleted += ops.size();
                } catch (Exception e) {
                    Log.e(TAG, "Erreur lors de la suppression du dernier lot de contacts", e);
                }
            }
        }

        Log.d(TAG, "Contacts GalSync supprimés: " + deleted);
    }

    private int syncContactsToDevice(List<EntraUser> users, SyncCallback callback) {
        ContentResolver resolver = context.getContentResolver();
        int syncedCount = 0;

        for (int i = 0; i < users.size(); i++) {
            EntraUser user = users.get(i);
            callback.onProgress(i + 1, users.size());

            if (user.getDisplayName() == null || user.getDisplayName().trim().isEmpty()) {
                Log.d(TAG, "Utilisateur ignoré (pas de nom): " + user.getEmail());
                continue;
            }

            ArrayList<ContentProviderOperation> ops = new ArrayList<>();

            // RawContact
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                    .build());

            // Nom
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                            user.getDisplayName())
                    .build());

            // Email
            if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, user.getEmail())
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE,
                                ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                        .build());
            }

            // Téléphone professionnel
            if (user.getPhoneNumber() != null && !user.getPhoneNumber().trim().isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER,
                                user.getPhoneNumber())
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                        .build());
            }

            // Téléphone mobile
            if (user.getMobilePhone() != null && !user.getMobilePhone().trim().isEmpty()) {
                if (user.getPhoneNumber() == null || !user.getMobilePhone().equals(user.getPhoneNumber())) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE,
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER,
                                    user.getMobilePhone())
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build());
                }
            }

            // Organisation
            if (user.getJobTitle() != null && !user.getJobTitle().trim().isEmpty()) {
                android.content.ContentProviderOperation.Builder orgBuilder =
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Organization.TYPE,
                                        ContactsContract.CommonDataKinds.Organization.TYPE_WORK);

                StringBuilder titleBuilder = new StringBuilder(user.getJobTitle());
                if (user.getDepartment() != null && !user.getDepartment().trim().isEmpty()) {
                    titleBuilder.append(" - ").append(user.getDepartment());
                }
                orgBuilder.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, titleBuilder.toString());

                if (user.getOfficeLocation() != null && !user.getOfficeLocation().trim().isEmpty()) {
                    orgBuilder.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY,
                            user.getOfficeLocation());
                }

                ops.add(orgBuilder.build());
            }

            // Manager
            if (user.getManagerName() != null && !user.getManagerName().trim().isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Relation.NAME,
                                user.getManagerName())
                        .withValue(ContactsContract.CommonDataKinds.Relation.TYPE,
                                ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER)
                        .build());
            }

            // Photo
            if (user.getPhotoData() != null && user.getPhotoData().length > 0) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO,
                                user.getPhotoData())
                        .build());
            }

            // Teams links
            if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Im.DATA,
                                user.getEmail())
                        .withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL,
                                ContactsContract.CommonDataKinds.Im.PROTOCOL_SKYPE)
                        .build());

                String teamsLink = "https://teams.microsoft.com/l/chat/0/0?users=" + user.getEmail();
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Website.URL,
                                teamsLink)
                        .withValue(ContactsContract.CommonDataKinds.Website.TYPE,
                                ContactsContract.CommonDataKinds.Website.TYPE_OTHER)
                        .withValue(ContactsContract.CommonDataKinds.Website.LABEL,
                                "Chat Teams")
                        .build());
            }

            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops);
                syncedCount++;
                Log.d(TAG, "Contact créé: " + user.getDisplayName() + " (" + ops.size() + " opérations)");
            } catch (Exception e) {
                Log.e(TAG, "Erreur lors de la création du contact: " + user.getDisplayName(), e);
            }
        }

        return syncedCount;
    }

    private List<EntraUser> fetchUsersFromGraph(String accessToken) {
        List<EntraUser> users = new ArrayList<>();

        try {
            Log.d(TAG, "=== DEBUT fetchUsersFromGraph ===");

            if (accessToken == null || accessToken.isEmpty()) {
                throw new Exception("Access token manquant");
            }

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            // If security groups are configured, fetch members from all groups
            Set<String> groupMemberIds = null;
            if (settingsManager.hasGroupFilter()) {
                boolean nested = settingsManager.isNestedGroupsEnabled();
                groupMemberIds = new HashSet<>();
                for (String gid : settingsManager.getSecurityGroupIds()) {
                    Set<String> ids = fetchGroupMemberIds(client, accessToken, gid, nested);
                    groupMemberIds.addAll(ids);
                    Log.d(TAG, "Groupe " + gid + (nested ? " (transitif)" : "") + ": " + ids.size() + " membres");
                }
                Log.d(TAG, "Filtre par groupe(s) de sécurité: " + groupMemberIds.size() + " membres au total");
            }

            // Build URL with optional attribute filter
            String url;
            if (settingsManager.hasAttributeFilter()) {
                String attr = settingsManager.getFilterAttribute().trim();
                // Sanitize filter value: escape single quotes for OData
                String val = settingsManager.getFilterValue().trim().replace("'", "''");
                url = "https://graph.microsoft.com/v1.0/users?$filter=accountEnabled eq true and " + attr + " eq '" + val + "'&$select=id,displayName,mail,businessPhones,mobilePhone,jobTitle,department,officeLocation,userPrincipalName&$top=999";
                Log.d(TAG, "Filtre par attribut: " + attr + " = " + val);
            } else {
                url = "https://graph.microsoft.com/v1.0/users?$filter=accountEnabled eq true&$select=id,displayName,mail,businessPhones,mobilePhone,jobTitle,department,officeLocation,userPrincipalName&$top=999";
            }

            Log.d(TAG, "URL: " + url);

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .build();

            okhttp3.Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Pas de détails";
                Log.e(TAG, "Erreur HTTP " + response.code() + ": " + errorBody);
                throw new Exception("Erreur HTTP " + response.code() + ": " + response.message());
            }

            String jsonResponse = response.body().string();
            com.google.gson.JsonObject jsonObject = com.google.gson.JsonParser.parseString(jsonResponse).getAsJsonObject();

            if (!jsonObject.has("value")) {
                throw new Exception("Format JSON invalide - pas de champ 'value'");
            }

            com.google.gson.JsonArray usersArray = jsonObject.getAsJsonArray("value");
            Log.d(TAG, "Nombre total d'utilisateurs actifs: " + usersArray.size());

            int ignoredNoJobTitle = 0;
            int ignoredNoDepartment = 0;
            int ignoredPrefix = 0;
            int ignoredNoName = 0;
            int processedCount = 0;

            for (int i = 0; i < usersArray.size(); i++) {
                try {
                    com.google.gson.JsonObject userJson = usersArray.get(i).getAsJsonObject();

                    String displayName = userJson.has("displayName") && !userJson.get("displayName").isJsonNull()
                            ? userJson.get("displayName").getAsString() : "";
                    String upn = userJson.has("userPrincipalName") && !userJson.get("userPrincipalName").isJsonNull()
                            ? userJson.get("userPrincipalName").getAsString() : "";

                    if (displayName.trim().isEmpty()) {
                        ignoredNoName++;
                        continue;
                    }

                    if (!userJson.has("jobTitle") || userJson.get("jobTitle").isJsonNull() ||
                            userJson.get("jobTitle").getAsString().trim().isEmpty()) {
                        ignoredNoJobTitle++;
                        continue;
                    }

                    if (!userJson.has("department") || userJson.get("department").isJsonNull() ||
                            userJson.get("department").getAsString().trim().isEmpty()) {
                        ignoredNoDepartment++;
                        continue;
                    }

                    String upnLower = upn.toLowerCase();
                    String[] excludedPrefixes = {"dev_", "adm_", "spe_", "sup_"};
                    boolean shouldExclude = false;

                    for (String prefix : excludedPrefixes) {
                        if (upnLower.startsWith(prefix)) {
                            shouldExclude = true;
                            ignoredPrefix++;
                            break;
                        }
                    }

                    if (shouldExclude) {
                        continue;
                    }

                    // Security group membership filter
                    if (groupMemberIds != null) {
                        String uid = userJson.has("id") && !userJson.get("id").isJsonNull()
                                ? userJson.get("id").getAsString() : "";
                        if (!groupMemberIds.contains(uid)) {
                            continue;
                        }
                    }

                    EntraUser entraUser = new EntraUser();

                    String userId = null;
                    if (userJson.has("id") && !userJson.get("id").isJsonNull()) {
                        userId = userJson.get("id").getAsString();
                        entraUser.setId(userId);
                    }

                    entraUser.setDisplayName(displayName);

                    if (userJson.has("mail") && !userJson.get("mail").isJsonNull()) {
                        entraUser.setEmail(userJson.get("mail").getAsString());
                    }

                    if (userJson.has("businessPhones") && !userJson.get("businessPhones").isJsonNull()) {
                        com.google.gson.JsonArray phones = userJson.getAsJsonArray("businessPhones");
                        if (phones.size() > 0 && !phones.get(0).isJsonNull()) {
                            entraUser.setPhoneNumber(phones.get(0).getAsString());
                        }
                    }

                    if (userJson.has("mobilePhone") && !userJson.get("mobilePhone").isJsonNull()) {
                        entraUser.setMobilePhone(userJson.get("mobilePhone").getAsString());
                    }

                    entraUser.setJobTitle(userJson.get("jobTitle").getAsString());
                    entraUser.setDepartment(userJson.get("department").getAsString());

                    if (userJson.has("officeLocation") && !userJson.get("officeLocation").isJsonNull()) {
                        entraUser.setOfficeLocation(userJson.get("officeLocation").getAsString());
                    }

                    users.add(entraUser);
                    processedCount++;

                } catch (Exception e) {
                    Log.e(TAG, "Erreur lors du traitement d'un utilisateur à l'index " + i, e);
                }
            }

            Log.d(TAG, "=== Récupération des managers et photos ===");

            int managerSuccessCount = 0;
            int photoSuccessCount = 0;

            for (int i = 0; i < users.size(); i++) {
                EntraUser user = users.get(i);

                if (user.getId() != null) {
                    try {
                        fetchUserManagerQuick(client, accessToken, user.getId(), user);
                        if (user.getManagerName() != null) managerSuccessCount++;
                    } catch (Exception e) {
                        // Pas grave
                    }

                    try {
                        fetchUserPhoto(client, accessToken, user.getId(), user);
                        if (user.getPhotoData() != null) photoSuccessCount++;
                    } catch (Exception e) {
                        // Pas grave
                    }
                }

                if ((i + 1) % 50 == 0) {
                    Log.d(TAG, "Progression managers/photos: " + (i + 1) + "/" + users.size());
                }
            }

            Log.d(TAG, "=== STATISTIQUES FINALES ===");
            Log.d(TAG, "Total actifs: " + usersArray.size() + ", Ignorés sans nom: " + ignoredNoName
                    + ", sans job: " + ignoredNoJobTitle + ", sans dept: " + ignoredNoDepartment
                    + ", préfixe exclu: " + ignoredPrefix + ", Valides: " + processedCount
                    + ", Managers: " + managerSuccessCount + ", Photos: " + photoSuccessCount);

        } catch (Exception e) {
            Log.e(TAG, "Erreur critique dans fetchUsersFromGraph", e);
            throw new RuntimeException(e);
        }

        return users;
    }

    private void fetchUserManagerQuick(okhttp3.OkHttpClient client, String accessToken, String userId, EntraUser entraUser) {
        try {
            String managerUrl = "https://graph.microsoft.com/v1.0/users/" + userId + "/manager?$select=displayName";

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(managerUrl)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            okhttp3.Response response = client.newCall(request).execute();

            if (response.isSuccessful() && response.body() != null) {
                String jsonResponse = response.body().string();
                com.google.gson.JsonObject managerJson = com.google.gson.JsonParser.parseString(jsonResponse).getAsJsonObject();

                if (managerJson.has("displayName") && !managerJson.get("displayName").isJsonNull()) {
                    entraUser.setManagerName(managerJson.get("displayName").getAsString());
                }
            } else if (response.body() != null) {
                response.body().close();
            }
        } catch (Exception e) {
            // Silencieux
        }
    }

    private void fetchUserPhoto(okhttp3.OkHttpClient client, String accessToken, String userId, EntraUser entraUser) {
        try {
            String photoUrl = "https://graph.microsoft.com/v1.0/users/" + userId + "/photo/$value";

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(photoUrl)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            okhttp3.Response response = client.newCall(request).execute();

            if (response.isSuccessful() && response.body() != null) {
                byte[] photoBytes = response.body().bytes();
                if (photoBytes.length > 0) {
                    entraUser.setPhotoData(photoBytes);
                }
            } else if (response.body() != null) {
                response.body().close();
            }
        } catch (Exception e) {
            // Silencieux
        }
    }

    private Set<String> fetchGroupMemberIds(okhttp3.OkHttpClient client, String accessToken, String groupId, boolean transitive) {
        Set<String> memberIds = new HashSet<>();
        try {
            String endpoint = transitive ? "/transitiveMembers" : "/members";
            String url = "https://graph.microsoft.com/v1.0/groups/" + groupId + endpoint + "?$select=id&$top=999";

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .build();

            okhttp3.Response response = client.newCall(request).execute();

            if (response.isSuccessful() && response.body() != null) {
                String jsonResponse = response.body().string();
                com.google.gson.JsonObject jsonObject = com.google.gson.JsonParser.parseString(jsonResponse).getAsJsonObject();

                if (jsonObject.has("value")) {
                    com.google.gson.JsonArray members = jsonObject.getAsJsonArray("value");
                    for (int i = 0; i < members.size(); i++) {
                        com.google.gson.JsonObject member = members.get(i).getAsJsonObject();
                        if (member.has("id") && !member.get("id").isJsonNull()) {
                            memberIds.add(member.get("id").getAsString());
                        }
                    }
                }
            } else {
                Log.e(TAG, "Erreur lors de la récupération des membres du groupe: " + response.code());
                if (response.body() != null) response.body().close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de la récupération des membres du groupe", e);
        }
        return memberIds;
    }
}
