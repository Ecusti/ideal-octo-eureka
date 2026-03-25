package com.trajets.galsync.sync;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.ContactsContract;
import android.util.Log;

import com.trajets.galsync.auth.AuthManager;
import com.trajets.galsync.models.EntraUser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContactSyncManager {
    private static final String TAG = "ContactSyncManager";
    private static final String ACCOUNT_TYPE = "com.trajets.galsync";
    private static final String ACCOUNT_NAME = "Fondation Trajets";

    private final Context context;
    private final AuthManager authManager;
    private final ExecutorService executor;

    public interface SyncCallback {
        void onProgress(int current, int total);
        void onComplete(int syncedCount);
        void onError(Exception exception);
    }

    public ContactSyncManager(Context context) {
        this.context = context;
        this.authManager = new AuthManager((android.app.Activity) context);
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void syncContacts(final SyncCallback callback) {
        executor.execute(new Runnable() {
            public void run() {
                try {
                    // Créer un AuthManager temporaire pour cette opération
                    final String[] tokenHolder = new String[1];
                    final Exception[] errorHolder = new Exception[1];
                    final Object lock = new Object();

                    // Utiliser le token existant de l'AuthManager principal
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
                            lock.wait(10000); // Attendre max 10 secondes
                        } catch (InterruptedException e) {
                            callback.onError(e);
                            return;
                        }
                    }

                    if (errorHolder[0] != null) {
                        callback.onError(errorHolder[0]);
                        return;
                    }

                    if (tokenHolder[0] == null) {
                        callback.onError(new Exception("Timeout lors de l'acquisition du token"));
                        return;
                    }

                    // Maintenant on est dans le thread en arrière-plan, on peut faire l'appel réseau
                    List<EntraUser> users = fetchUsersFromGraph(tokenHolder[0]);
                    int syncedCount = syncContactsToDevice(users, callback);
                    callback.onComplete(syncedCount);

                } catch (Exception e) {
                    Log.e(TAG, "Erreur de synchronisation", e);
                    callback.onError(e);
                }
            }
        });
    }

    private int syncContactsToDevice(List<EntraUser> users, SyncCallback callback) {
        ContentResolver resolver = context.getContentResolver();
        int syncedCount = 0;

        for (int i = 0; i < users.size(); i++) {
            EntraUser user = users.get(i);
            callback.onProgress(i + 1, users.size());

            // Vérifier que l'utilisateur a au moins un nom
            if (user.getDisplayName() == null || user.getDisplayName().trim().isEmpty()) {
                Log.d(TAG, "Utilisateur ignoré (pas de nom): " + user.getEmail());
                continue;
            }

            // Créer un nouveau batch d'opérations pour CHAQUE contact
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();

            // Ajouter le RawContact
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                    .build());

            // Nom (index 0 dans le batch)
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

            // Téléphone mobile (seulement s'il est différent du téléphone professionnel)
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

            // Organisation : UNE SEULE ENTRÉE avec fonction - département
            if (user.getJobTitle() != null && !user.getJobTitle().trim().isEmpty()) {
                android.content.ContentProviderOperation.Builder orgBuilder =
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Organization.TYPE,
                                        ContactsContract.CommonDataKinds.Organization.TYPE_WORK);

                // Construire le titre: "Fonction - Département"
                StringBuilder titleBuilder = new StringBuilder(user.getJobTitle());
                if (user.getDepartment() != null && !user.getDepartment().trim().isEmpty()) {
                    titleBuilder.append(" - ").append(user.getDepartment());
                }
                orgBuilder.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, titleBuilder.toString());

                // Ajouter le bureau comme "Company" si disponible
                if (user.getOfficeLocation() != null && !user.getOfficeLocation().trim().isEmpty()) {
                    orgBuilder.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY,
                            user.getOfficeLocation());
                }

                ops.add(orgBuilder.build());
            }

            // Manager (Relation)
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

            // Photo de profil
            if (user.getPhotoData() != null && user.getPhotoData().length > 0) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO,
                                user.getPhotoData())
                        .build());
            }

            // Lien Teams - Via IM Protocol pour ouvrir l'app Teams
            if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
                // Lien pour ouvrir Teams directement
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Im.DATA,
                                user.getEmail())
                        .withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL,
                                ContactsContract.CommonDataKinds.Im.PROTOCOL_SKYPE)
                        .build());

                // ET ajouter aussi comme website pour compatibilité
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
                                "💬 Chat Teams")
                        .build());
            }

            // Appliquer les opérations pour CE contact immédiatement
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
            Log.d(TAG, "Access token présent: " + (accessToken != null && !accessToken.isEmpty()));

            if (accessToken == null || accessToken.isEmpty()) {
                throw new Exception("Access token manquant");
            }

            Log.d(TAG, "Récupération de tous les utilisateurs actifs...");
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            // Récupérer tous les utilisateurs SANS expand pour éviter les erreurs
            String url = "https://graph.microsoft.com/v1.0/users?$filter=accountEnabled eq true&$select=id,displayName,mail,businessPhones,mobilePhone,jobTitle,department,officeLocation,userPrincipalName&$top=999";

            Log.d(TAG, "URL: " + url);

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Log.d(TAG, "Envoi de la requête...");
            okhttp3.Response response = client.newCall(request).execute();

            Log.d(TAG, "Code réponse HTTP: " + response.code());

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Pas de détails";
                Log.e(TAG, "Erreur HTTP " + response.code() + ": " + errorBody);
                throw new Exception("Erreur HTTP " + response.code() + ": " + response.message());
            }

            String jsonResponse = response.body().string();
            Log.d(TAG, "Réponse JSON reçue (longueur: " + jsonResponse.length() + ")");

            // Parser le JSON avec Gson
            com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
            com.google.gson.JsonObject jsonObject = parser.parse(jsonResponse).getAsJsonObject();

            if (!jsonObject.has("value")) {
                Log.e(TAG, "Pas de champ 'value' dans la réponse JSON");
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

                    // FILTRE 1: Vérifier que displayName n'est pas vide
                    if (displayName.trim().isEmpty()) {
                        ignoredNoName++;
                        Log.d(TAG, "Ignoré (pas de nom): " + upn);
                        continue;
                    }

                    // FILTRE 2: Vérifier que jobTitle n'est pas vide
                    if (!userJson.has("jobTitle") || userJson.get("jobTitle").isJsonNull() ||
                            userJson.get("jobTitle").getAsString().trim().isEmpty()) {
                        ignoredNoJobTitle++;
                        Log.d(TAG, "Ignoré (pas de job title): " + displayName);
                        continue;
                    }

                    // FILTRE 3: Vérifier que department n'est pas vide
                    if (!userJson.has("department") || userJson.get("department").isJsonNull() ||
                            userJson.get("department").getAsString().trim().isEmpty()) {
                        ignoredNoDepartment++;
                        Log.d(TAG, "Ignoré (pas de département): " + displayName);
                        continue;
                    }

                    // FILTRE 4: Vérifier le userPrincipalName pour les préfixes à exclure
                    String upnLower = upn.toLowerCase();
                    String[] excludedPrefixes = {"dev_", "adm_", "spe_", "sup_"};
                    boolean shouldExclude = false;

                    for (String prefix : excludedPrefixes) {
                        if (upnLower.startsWith(prefix)) {
                            shouldExclude = true;
                            ignoredPrefix++;
                            Log.d(TAG, "Ignoré (préfixe exclu): " + upn);
                            break;
                        }
                    }

                    if (shouldExclude) {
                        continue;
                    }

                    // Créer l'utilisateur - tous les filtres sont passés
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

                    // Téléphones professionnels
                    if (userJson.has("businessPhones") && !userJson.get("businessPhones").isJsonNull()) {
                        com.google.gson.JsonArray phones = userJson.getAsJsonArray("businessPhones");
                        if (phones.size() > 0 && !phones.get(0).isJsonNull()) {
                            entraUser.setPhoneNumber(phones.get(0).getAsString());
                        }
                    }

                    // Téléphone mobile
                    if (userJson.has("mobilePhone") && !userJson.get("mobilePhone").isJsonNull()) {
                        entraUser.setMobilePhone(userJson.get("mobilePhone").getAsString());
                    }

                    // JobTitle
                    entraUser.setJobTitle(userJson.get("jobTitle").getAsString());

                    // Department
                    entraUser.setDepartment(userJson.get("department").getAsString());

                    if (userJson.has("officeLocation") && !userJson.get("officeLocation").isJsonNull()) {
                        entraUser.setOfficeLocation(userJson.get("officeLocation").getAsString());
                    }

                    users.add(entraUser);
                    processedCount++;

                    Log.d(TAG, "Utilisateur " + processedCount + " ajouté: " + displayName +
                            " (Job: " + entraUser.getJobTitle() + ", Dept: " + entraUser.getDepartment() + ")");

                } catch (Exception e) {
                    Log.e(TAG, "Erreur lors du traitement d'un utilisateur à l'index " + i, e);
                }
            }

            Log.d(TAG, "=== PHASE 1 TERMINÉE - Récupération des managers et photos ===");

            // Récupérer les managers et photos en lot après avoir filtré
            int managerSuccessCount = 0;
            int photoSuccessCount = 0;

            for (int i = 0; i < users.size(); i++) {
                EntraUser user = users.get(i);

                if (user.getId() != null) {
                    // Manager
                    try {
                        fetchUserManagerQuick(client, accessToken, user.getId(), user);
                        if (user.getManagerName() != null) {
                            managerSuccessCount++;
                        }
                    } catch (Exception e) {
                        // Pas grave si pas de manager
                    }

                    // Photo
                    try {
                        fetchUserPhoto(client, accessToken, user.getId(), user);
                        if (user.getPhotoData() != null) {
                            photoSuccessCount++;
                        }
                    } catch (Exception e) {
                        // Pas grave si pas de photo
                    }
                }

                // Log tous les 50
                if ((i + 1) % 50 == 0) {
                    Log.d(TAG, "Progression managers/photos: " + (i + 1) + "/" + users.size());
                }
            }

            Log.d(TAG, "=== STATISTIQUES FINALES ===");
            Log.d(TAG, "Total utilisateurs actifs dans l'annuaire: " + usersArray.size());
            Log.d(TAG, "Ignorés (sans nom): " + ignoredNoName);
            Log.d(TAG, "Ignorés (sans job title): " + ignoredNoJobTitle);
            Log.d(TAG, "Ignorés (sans département): " + ignoredNoDepartment);
            Log.d(TAG, "Ignorés (préfixe dev_/adm_/spe_/sup_): " + ignoredPrefix);
            Log.d(TAG, "Utilisateurs valides: " + processedCount);
            Log.d(TAG, "Managers récupérés: " + managerSuccessCount);
            Log.d(TAG, "Photos récupérées: " + photoSuccessCount);
            Log.d(TAG, "=== FIN fetchUsersFromGraph ===");

        } catch (Exception e) {
            Log.e(TAG, "=== ERREUR CRITIQUE dans fetchUsersFromGraph ===", e);
            Log.e(TAG, "Type d'erreur: " + e.getClass().getName());
            Log.e(TAG, "Message: " + e.getMessage());
            e.printStackTrace();

            EntraUser errorUser = new EntraUser();
            errorUser.setDisplayName("Erreur Graph API");
            errorUser.setEmail("Erreur: " + e.getMessage());
            users.add(errorUser);
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
                com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
                com.google.gson.JsonObject managerJson = parser.parse(jsonResponse).getAsJsonObject();

                if (managerJson.has("displayName") && !managerJson.get("displayName").isJsonNull()) {
                    entraUser.setManagerName(managerJson.get("displayName").getAsString());
                }
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
            }
        } catch (Exception e) {
            // Silencieux
        }
    }

    private void fetchUserManager(okhttp3.OkHttpClient client, String accessToken, String userId, EntraUser entraUser) {
        try {
            String managerUrl = "https://graph.microsoft.com/v1.0/users/" + userId + "/manager?$select=id,displayName";

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(managerUrl)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            okhttp3.Response response = client.newCall(request).execute();

            if (response.isSuccessful()) {
                String jsonResponse = response.body().string();
                com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
                com.google.gson.JsonObject managerJson = parser.parse(jsonResponse).getAsJsonObject();

                if (managerJson.has("id") && !managerJson.get("id").isJsonNull()) {
                    entraUser.setManagerId(managerJson.get("id").getAsString());
                }

                if (managerJson.has("displayName") && !managerJson.get("displayName").isJsonNull()) {
                    entraUser.setManagerName(managerJson.get("displayName").getAsString());
                }

                Log.d(TAG, "Manager récupéré pour " + entraUser.getDisplayName() + ": " + entraUser.getManagerName());
            }
        } catch (Exception e) {
            Log.d(TAG, "Pas de manager pour " + entraUser.getDisplayName());
        }
    }


}