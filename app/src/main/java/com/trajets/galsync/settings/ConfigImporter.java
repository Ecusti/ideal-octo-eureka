package com.trajets.galsync.settings;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Parses Entra ID configuration from a JSON string (inline or downloaded from a URL).
 *
 * Design contract:
 * - No validation is done here. Raw values are returned so the UI can display them.
 * - Validation (UUID format, attribute name, etc.) happens only when the user taps Save.
 * - A field present in the JSON (even empty) is returned as a String.
 * - A field absent from the JSON is returned as null, which tells the UI to clear that field.
 *
 * Expected JSON format:
 * {
 *   "client_id":        "uuid",           // required
 *   "tenant_id":        "uuid",           // required
 *   "redirect_uri":     "optional-string",
 *   "security_group_id":"optional-uuid",
 *   "filter_attribute": "optional-string",
 *   "filter_value":     "optional-string"
 * }
 */
public class ConfigImporter {

    /** All fields are nullable: null means the JSON did not contain that key. */
    public static class ImportedConfig {
        public final String clientId;
        public final String tenantId;
        public final String redirectUri;
        public final String securityGroupId;
        public final Boolean nestedGroups;
        public final String filterAttribute;
        public final String filterValue;

        ImportedConfig(String clientId, String tenantId, String redirectUri,
                       String securityGroupId, Boolean nestedGroups,
                       String filterAttribute, String filterValue) {
            this.clientId = clientId;
            this.tenantId = tenantId;
            this.redirectUri = redirectUri;
            this.securityGroupId = securityGroupId;
            this.nestedGroups = nestedGroups;
            this.filterAttribute = filterAttribute;
            this.filterValue = filterValue;
        }

        /** Number of fields actually present in the JSON (non-null). */
        public int fieldCount() {
            int count = 0;
            if (clientId != null) count++;
            if (tenantId != null) count++;
            if (redirectUri != null) count++;
            if (securityGroupId != null) count++;
            if (nestedGroups != null) count++;
            if (filterAttribute != null) count++;
            if (filterValue != null) count++;
            return count;
        }
    }

    public interface ImportCallback {
        void onSuccess(ImportedConfig config);
        void onError(String message);
    }

    private static final int MAX_JSON_BYTES = 10_000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Parse a JSON string and return an ImportedConfig via the callback.
     * Always called on the thread the caller is on; callback is called on the same thread.
     */
    public void importFromJson(String json, ImportCallback callback) {
        if (json == null || json.trim().isEmpty()) {
            callback.onError("Le contenu est vide");
            return;
        }
        if (json.length() > MAX_JSON_BYTES) {
            callback.onError("Le contenu est trop volumineux (max 10 Ko)");
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(json.trim()).getAsJsonObject();
            ImportedConfig config = parseConfig(obj);
            if (config.fieldCount() == 0) {
                callback.onError("Aucun champ reconnu dans la configuration");
            } else {
                callback.onSuccess(config);
            }
        } catch (JsonSyntaxException e) {
            callback.onError("Format JSON invalide");
        } catch (IllegalStateException e) {
            callback.onError("La configuration doit être un objet JSON");
        }
    }

    /**
     * Download JSON from a URL and return an ImportedConfig via the callback.
     * Callback is always delivered on the main thread.
     * Only HTTPS URLs are accepted to prevent sending credentials over plaintext.
     */
    public void importFromUrl(String url, ImportCallback callback) {
        if (url == null || url.trim().isEmpty()) {
            callback.onError("L'URL ne peut pas être vide");
            return;
        }

        String trimmed = url.trim();
        if (!trimmed.startsWith("https://")) {
            callback.onError("L'URL doit commencer par https://");
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        Request request = new Request.Builder()
                .url(trimmed)
                .header("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Erreur réseau : " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        mainHandler.post(() ->
                                callback.onError("Erreur HTTP " + response.code()));
                        return;
                    }
                    if (body == null) {
                        mainHandler.post(() -> callback.onError("Réponse vide du serveur"));
                        return;
                    }
                    // Guard against unexpectedly large responses
                    long contentLength = body.contentLength();
                    if (contentLength > MAX_JSON_BYTES) {
                        mainHandler.post(() ->
                                callback.onError("Fichier trop volumineux (max 10 Ko)"));
                        return;
                    }
                    String json = body.string();
                    if (json.length() > MAX_JSON_BYTES) {
                        mainHandler.post(() ->
                                callback.onError("Fichier trop volumineux (max 10 Ko)"));
                        return;
                    }
                    mainHandler.post(() -> importFromJson(json, callback));
                } catch (IOException e) {
                    mainHandler.post(() ->
                            callback.onError("Erreur de lecture : " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Extract all known fields from the JSON object.
     * Returns null for any key that is absent from the JSON.
     * Returns the raw trimmed string (possibly empty) for keys that are present.
     */
    private static ImportedConfig parseConfig(JsonObject obj) {
        return new ImportedConfig(
                extractString(obj, "client_id"),
                extractString(obj, "tenant_id"),
                extractString(obj, "redirect_uri"),
                extractString(obj, "security_group_id"),
                extractBoolean(obj, "nested_groups"),
                extractString(obj, "filter_attribute"),
                extractString(obj, "filter_value")
        );
    }

    /**
     * Returns the boolean value for the key, or null if absent or not a boolean.
     */
    private static Boolean extractBoolean(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the trimmed string value for the key, or null if the key is absent or null in JSON.
     */
    private static String extractString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
