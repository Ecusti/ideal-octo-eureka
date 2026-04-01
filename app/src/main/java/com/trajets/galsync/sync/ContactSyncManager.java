package com.trajets.galsync.sync;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import android.telephony.TelephonyManager;

import com.trajets.galsync.auth.AuthManager;
import com.trajets.galsync.models.EntraUser;
import com.trajets.galsync.settings.SettingsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        boolean autoCountryCode = settingsManager.isAutoCountryCodeEnabled();

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
            String workPhone = user.getPhoneNumber();
            if (autoCountryCode && workPhone != null) {
                workPhone = formatPhoneWithCountryCode(workPhone, user.getUsageLocation());
            }
            if (workPhone != null && !workPhone.trim().isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER,
                                workPhone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                        .build());
            }

            // Téléphone mobile
            String mobilePhone = user.getMobilePhone();
            if (autoCountryCode && mobilePhone != null) {
                mobilePhone = formatPhoneWithCountryCode(mobilePhone, user.getUsageLocation());
            }
            if (mobilePhone != null && !mobilePhone.trim().isEmpty()) {
                if (workPhone == null || !mobilePhone.equals(workPhone)) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE,
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER,
                                    mobilePhone)
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
                url = "https://graph.microsoft.com/v1.0/users?$filter=accountEnabled eq true and " + attr + " eq '" + val + "'&$select=id,displayName,mail,businessPhones,mobilePhone,jobTitle,department,officeLocation,userPrincipalName,usageLocation&$top=999";
                Log.d(TAG, "Filtre par attribut: " + attr + " = " + val);
            } else {
                url = "https://graph.microsoft.com/v1.0/users?$filter=accountEnabled eq true&$select=id,displayName,mail,businessPhones,mobilePhone,jobTitle,department,officeLocation,userPrincipalName,usageLocation&$top=999";
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

                    if (userJson.has("usageLocation") && !userJson.get("usageLocation").isJsonNull()) {
                        entraUser.setUsageLocation(userJson.get("usageLocation").getAsString());
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

    /**
     * If auto country code is enabled, prepend the international dialing prefix
     * to phone numbers that don't already have one (starting with + or 00).
     * The country is determined from the user's Entra ID usageLocation property,
     * falling back to the device's SIM/network/locale if usageLocation is not set.
     */
    private String formatPhoneWithCountryCode(String phone, String usageLocation) {
        if (phone == null || phone.trim().isEmpty()) return phone;

        String trimmed = phone.trim();
        // Already has an international prefix
        if (trimmed.startsWith("+") || trimmed.startsWith("00")) {
            return trimmed;
        }

        // Use the user's usageLocation first, fall back to device country
        String dialCode = null;
        if (usageLocation != null && !usageLocation.trim().isEmpty()) {
            dialCode = COUNTRY_DIAL_CODES.get(usageLocation.trim().toUpperCase());
            if (dialCode != null) {
                Log.d(TAG, "usageLocation: " + usageLocation + " -> dial code: " + dialCode);
            }
        }
        if (dialCode == null) {
            dialCode = getDeviceCountryDialCode();
        }
        if (dialCode == null) return trimmed;

        // Remove leading 0 (local trunk prefix) before prepending country code
        if (trimmed.startsWith("0")) {
            trimmed = trimmed.substring(1);
        }

        return dialCode + trimmed;
    }

    /**
     * Fallback: returns the international dialing code based on the device's
     * SIM country, network country, or locale. Only used when the Entra ID
     * user has no usageLocation set.
     */
    private String getDeviceCountryDialCode() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            String countryIso = null;
            if (tm != null) {
                countryIso = tm.getSimCountryIso();
                if (countryIso == null || countryIso.isEmpty()) {
                    countryIso = tm.getNetworkCountryIso();
                }
            }
            if (countryIso == null || countryIso.isEmpty()) {
                countryIso = context.getResources().getConfiguration().getLocales().get(0).getCountry();
            }
            if (countryIso == null || countryIso.isEmpty()) return null;

            String dialCode = COUNTRY_DIAL_CODES.get(countryIso.toUpperCase());
            if (dialCode != null) {
                Log.d(TAG, "Device country ISO: " + countryIso + " -> dial code: " + dialCode);
            }
            return dialCode;
        } catch (Exception e) {
            Log.w(TAG, "Could not determine country dial code", e);
            return null;
        }
    }

    private static final Map<String, String> COUNTRY_DIAL_CODES = new HashMap<>();
    static {
        COUNTRY_DIAL_CODES.put("AF", "+93");
        COUNTRY_DIAL_CODES.put("AL", "+355");
        COUNTRY_DIAL_CODES.put("DZ", "+213");
        COUNTRY_DIAL_CODES.put("AD", "+376");
        COUNTRY_DIAL_CODES.put("AO", "+244");
        COUNTRY_DIAL_CODES.put("AR", "+54");
        COUNTRY_DIAL_CODES.put("AM", "+374");
        COUNTRY_DIAL_CODES.put("AU", "+61");
        COUNTRY_DIAL_CODES.put("AT", "+43");
        COUNTRY_DIAL_CODES.put("AZ", "+994");
        COUNTRY_DIAL_CODES.put("BH", "+973");
        COUNTRY_DIAL_CODES.put("BD", "+880");
        COUNTRY_DIAL_CODES.put("BY", "+375");
        COUNTRY_DIAL_CODES.put("BE", "+32");
        COUNTRY_DIAL_CODES.put("BZ", "+501");
        COUNTRY_DIAL_CODES.put("BJ", "+229");
        COUNTRY_DIAL_CODES.put("BT", "+975");
        COUNTRY_DIAL_CODES.put("BO", "+591");
        COUNTRY_DIAL_CODES.put("BA", "+387");
        COUNTRY_DIAL_CODES.put("BW", "+267");
        COUNTRY_DIAL_CODES.put("BR", "+55");
        COUNTRY_DIAL_CODES.put("BN", "+673");
        COUNTRY_DIAL_CODES.put("BG", "+359");
        COUNTRY_DIAL_CODES.put("BF", "+226");
        COUNTRY_DIAL_CODES.put("BI", "+257");
        COUNTRY_DIAL_CODES.put("KH", "+855");
        COUNTRY_DIAL_CODES.put("CM", "+237");
        COUNTRY_DIAL_CODES.put("CA", "+1");
        COUNTRY_DIAL_CODES.put("CV", "+238");
        COUNTRY_DIAL_CODES.put("CF", "+236");
        COUNTRY_DIAL_CODES.put("TD", "+235");
        COUNTRY_DIAL_CODES.put("CL", "+56");
        COUNTRY_DIAL_CODES.put("CN", "+86");
        COUNTRY_DIAL_CODES.put("CO", "+57");
        COUNTRY_DIAL_CODES.put("KM", "+269");
        COUNTRY_DIAL_CODES.put("CG", "+242");
        COUNTRY_DIAL_CODES.put("CD", "+243");
        COUNTRY_DIAL_CODES.put("CR", "+506");
        COUNTRY_DIAL_CODES.put("CI", "+225");
        COUNTRY_DIAL_CODES.put("HR", "+385");
        COUNTRY_DIAL_CODES.put("CU", "+53");
        COUNTRY_DIAL_CODES.put("CY", "+357");
        COUNTRY_DIAL_CODES.put("CZ", "+420");
        COUNTRY_DIAL_CODES.put("DK", "+45");
        COUNTRY_DIAL_CODES.put("DJ", "+253");
        COUNTRY_DIAL_CODES.put("DO", "+1");
        COUNTRY_DIAL_CODES.put("EC", "+593");
        COUNTRY_DIAL_CODES.put("EG", "+20");
        COUNTRY_DIAL_CODES.put("SV", "+503");
        COUNTRY_DIAL_CODES.put("GQ", "+240");
        COUNTRY_DIAL_CODES.put("ER", "+291");
        COUNTRY_DIAL_CODES.put("EE", "+372");
        COUNTRY_DIAL_CODES.put("ET", "+251");
        COUNTRY_DIAL_CODES.put("FI", "+358");
        COUNTRY_DIAL_CODES.put("FR", "+33");
        COUNTRY_DIAL_CODES.put("GA", "+241");
        COUNTRY_DIAL_CODES.put("GM", "+220");
        COUNTRY_DIAL_CODES.put("GE", "+995");
        COUNTRY_DIAL_CODES.put("DE", "+49");
        COUNTRY_DIAL_CODES.put("GH", "+233");
        COUNTRY_DIAL_CODES.put("GR", "+30");
        COUNTRY_DIAL_CODES.put("GT", "+502");
        COUNTRY_DIAL_CODES.put("GN", "+224");
        COUNTRY_DIAL_CODES.put("GW", "+245");
        COUNTRY_DIAL_CODES.put("GY", "+592");
        COUNTRY_DIAL_CODES.put("HT", "+509");
        COUNTRY_DIAL_CODES.put("HN", "+504");
        COUNTRY_DIAL_CODES.put("HK", "+852");
        COUNTRY_DIAL_CODES.put("HU", "+36");
        COUNTRY_DIAL_CODES.put("IS", "+354");
        COUNTRY_DIAL_CODES.put("IN", "+91");
        COUNTRY_DIAL_CODES.put("ID", "+62");
        COUNTRY_DIAL_CODES.put("IR", "+98");
        COUNTRY_DIAL_CODES.put("IQ", "+964");
        COUNTRY_DIAL_CODES.put("IE", "+353");
        COUNTRY_DIAL_CODES.put("IL", "+972");
        COUNTRY_DIAL_CODES.put("IT", "+39");
        COUNTRY_DIAL_CODES.put("JM", "+1");
        COUNTRY_DIAL_CODES.put("JP", "+81");
        COUNTRY_DIAL_CODES.put("JO", "+962");
        COUNTRY_DIAL_CODES.put("KZ", "+7");
        COUNTRY_DIAL_CODES.put("KE", "+254");
        COUNTRY_DIAL_CODES.put("KW", "+965");
        COUNTRY_DIAL_CODES.put("KG", "+996");
        COUNTRY_DIAL_CODES.put("LA", "+856");
        COUNTRY_DIAL_CODES.put("LV", "+371");
        COUNTRY_DIAL_CODES.put("LB", "+961");
        COUNTRY_DIAL_CODES.put("LS", "+266");
        COUNTRY_DIAL_CODES.put("LR", "+231");
        COUNTRY_DIAL_CODES.put("LY", "+218");
        COUNTRY_DIAL_CODES.put("LI", "+423");
        COUNTRY_DIAL_CODES.put("LT", "+370");
        COUNTRY_DIAL_CODES.put("LU", "+352");
        COUNTRY_DIAL_CODES.put("MO", "+853");
        COUNTRY_DIAL_CODES.put("MK", "+389");
        COUNTRY_DIAL_CODES.put("MG", "+261");
        COUNTRY_DIAL_CODES.put("MW", "+265");
        COUNTRY_DIAL_CODES.put("MY", "+60");
        COUNTRY_DIAL_CODES.put("MV", "+960");
        COUNTRY_DIAL_CODES.put("ML", "+223");
        COUNTRY_DIAL_CODES.put("MT", "+356");
        COUNTRY_DIAL_CODES.put("MR", "+222");
        COUNTRY_DIAL_CODES.put("MU", "+230");
        COUNTRY_DIAL_CODES.put("MX", "+52");
        COUNTRY_DIAL_CODES.put("MD", "+373");
        COUNTRY_DIAL_CODES.put("MC", "+377");
        COUNTRY_DIAL_CODES.put("MN", "+976");
        COUNTRY_DIAL_CODES.put("ME", "+382");
        COUNTRY_DIAL_CODES.put("MA", "+212");
        COUNTRY_DIAL_CODES.put("MZ", "+258");
        COUNTRY_DIAL_CODES.put("MM", "+95");
        COUNTRY_DIAL_CODES.put("NA", "+264");
        COUNTRY_DIAL_CODES.put("NP", "+977");
        COUNTRY_DIAL_CODES.put("NL", "+31");
        COUNTRY_DIAL_CODES.put("NZ", "+64");
        COUNTRY_DIAL_CODES.put("NI", "+505");
        COUNTRY_DIAL_CODES.put("NE", "+227");
        COUNTRY_DIAL_CODES.put("NG", "+234");
        COUNTRY_DIAL_CODES.put("KP", "+850");
        COUNTRY_DIAL_CODES.put("NO", "+47");
        COUNTRY_DIAL_CODES.put("OM", "+968");
        COUNTRY_DIAL_CODES.put("PK", "+92");
        COUNTRY_DIAL_CODES.put("PA", "+507");
        COUNTRY_DIAL_CODES.put("PG", "+675");
        COUNTRY_DIAL_CODES.put("PY", "+595");
        COUNTRY_DIAL_CODES.put("PE", "+51");
        COUNTRY_DIAL_CODES.put("PH", "+63");
        COUNTRY_DIAL_CODES.put("PL", "+48");
        COUNTRY_DIAL_CODES.put("PT", "+351");
        COUNTRY_DIAL_CODES.put("PR", "+1");
        COUNTRY_DIAL_CODES.put("QA", "+974");
        COUNTRY_DIAL_CODES.put("RO", "+40");
        COUNTRY_DIAL_CODES.put("RU", "+7");
        COUNTRY_DIAL_CODES.put("RW", "+250");
        COUNTRY_DIAL_CODES.put("SA", "+966");
        COUNTRY_DIAL_CODES.put("SN", "+221");
        COUNTRY_DIAL_CODES.put("RS", "+381");
        COUNTRY_DIAL_CODES.put("SL", "+232");
        COUNTRY_DIAL_CODES.put("SG", "+65");
        COUNTRY_DIAL_CODES.put("SK", "+421");
        COUNTRY_DIAL_CODES.put("SI", "+386");
        COUNTRY_DIAL_CODES.put("SO", "+252");
        COUNTRY_DIAL_CODES.put("ZA", "+27");
        COUNTRY_DIAL_CODES.put("KR", "+82");
        COUNTRY_DIAL_CODES.put("SS", "+211");
        COUNTRY_DIAL_CODES.put("ES", "+34");
        COUNTRY_DIAL_CODES.put("LK", "+94");
        COUNTRY_DIAL_CODES.put("SD", "+249");
        COUNTRY_DIAL_CODES.put("SR", "+597");
        COUNTRY_DIAL_CODES.put("SZ", "+268");
        COUNTRY_DIAL_CODES.put("SE", "+46");
        COUNTRY_DIAL_CODES.put("CH", "+41");
        COUNTRY_DIAL_CODES.put("SY", "+963");
        COUNTRY_DIAL_CODES.put("TW", "+886");
        COUNTRY_DIAL_CODES.put("TJ", "+992");
        COUNTRY_DIAL_CODES.put("TZ", "+255");
        COUNTRY_DIAL_CODES.put("TH", "+66");
        COUNTRY_DIAL_CODES.put("TL", "+670");
        COUNTRY_DIAL_CODES.put("TG", "+228");
        COUNTRY_DIAL_CODES.put("TN", "+216");
        COUNTRY_DIAL_CODES.put("TR", "+90");
        COUNTRY_DIAL_CODES.put("TM", "+993");
        COUNTRY_DIAL_CODES.put("UG", "+256");
        COUNTRY_DIAL_CODES.put("UA", "+380");
        COUNTRY_DIAL_CODES.put("AE", "+971");
        COUNTRY_DIAL_CODES.put("GB", "+44");
        COUNTRY_DIAL_CODES.put("US", "+1");
        COUNTRY_DIAL_CODES.put("UY", "+598");
        COUNTRY_DIAL_CODES.put("UZ", "+998");
        COUNTRY_DIAL_CODES.put("VE", "+58");
        COUNTRY_DIAL_CODES.put("VN", "+84");
        COUNTRY_DIAL_CODES.put("YE", "+967");
        COUNTRY_DIAL_CODES.put("ZM", "+260");
        COUNTRY_DIAL_CODES.put("ZW", "+263");
    }
}
