package com.trajets.galsync.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Handles importing Entra ID configuration from JSON (QR code content or URL).
 *
 * Expected JSON format:
 * {
 *   "client_id": "uuid",
 *   "tenant_id": "uuid",
 *   "redirect_uri": "optional-string",
 *   "security_group_id": "optional-uuid",
 *   "filter_attribute": "optional-string",
 *   "filter_value": "optional-string"
 * }
 */
public class ConfigImporter {

    public interface ImportCallback {
        void onSuccess(int fieldsImported);
        void onError(String message);
    }

    private final SettingsManager settingsManager;
    private final Handler mainHandler;

    public ConfigImporter(Context context) {
        this.settingsManager = new SettingsManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Parse JSON config string (from QR code or downloaded file) and apply to settings.
     */
    public void importFromJson(String json, ImportCallback callback) {
        try {
            JsonObject config = JsonParser.parseString(json).getAsJsonObject();
            int count = applyConfig(config);
            if (count == 0) {
                callback.onError("Aucun champ valide trouvé dans la configuration");
            } else {
                settingsManager.generateAuthConfig();
                callback.onSuccess(count);
            }
        } catch (JsonSyntaxException e) {
            callback.onError("Format JSON invalide");
        } catch (IllegalStateException e) {
            callback.onError("La configuration doit être un objet JSON");
        }
    }

    /**
     * Download JSON config from a URL and apply to settings.
     */
    public void importFromUrl(String url, ImportCallback callback) {
        if (url == null || url.trim().isEmpty()) {
            callback.onError("L'URL ne peut pas être vide");
            return;
        }

        String trimmedUrl = url.trim();
        if (!trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("http://")) {
            callback.onError("L'URL doit commencer par https:// ou http://");
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(trimmedUrl)
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
                        mainHandler.post(() -> callback.onError("Erreur HTTP " + response.code()));
                        return;
                    }
                    if (body == null) {
                        mainHandler.post(() -> callback.onError("Réponse vide du serveur"));
                        return;
                    }

                    long contentLength = body.contentLength();
                    if (contentLength > 10_000) {
                        mainHandler.post(() -> callback.onError("Fichier trop volumineux (max 10 Ko)"));
                        return;
                    }

                    String json = body.string();
                    if (json.length() > 10_000) {
                        mainHandler.post(() -> callback.onError("Fichier trop volumineux (max 10 Ko)"));
                        return;
                    }

                    mainHandler.post(() -> importFromJson(json, callback));
                } catch (IOException e) {
                    mainHandler.post(() -> callback.onError("Erreur de lecture : " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Apply config fields from a parsed JSON object. Returns the number of fields imported.
     */
    private int applyConfig(JsonObject config) {
        int count = 0;

        // Required fields
        if (config.has("client_id")) {
            String val = config.get("client_id").getAsString().trim();
            if (SettingsManager.isValidUuid(val)) {
                settingsManager.setClientId(val);
                count++;
            }
        }

        if (config.has("tenant_id")) {
            String val = config.get("tenant_id").getAsString().trim();
            if (SettingsManager.isValidUuid(val)) {
                settingsManager.setTenantId(val);
                count++;
            }
        }

        // Optional fields
        if (config.has("redirect_uri")) {
            String val = config.get("redirect_uri").getAsString().trim();
            if (!val.isEmpty()) {
                settingsManager.setRedirectUri(val);
                count++;
            }
        }

        if (config.has("security_group_id")) {
            String val = config.get("security_group_id").getAsString().trim();
            if (val.isEmpty() || SettingsManager.isValidUuid(val)) {
                settingsManager.setSecurityGroupId(val);
                count++;
            }
        }

        if (config.has("filter_attribute")) {
            String val = config.get("filter_attribute").getAsString().trim();
            if (val.isEmpty() || SettingsManager.isValidAttributeName(val)) {
                settingsManager.setFilterAttribute(val);
                count++;
            }
        }

        if (config.has("filter_value")) {
            String val = config.get("filter_value").getAsString().trim();
            settingsManager.setFilterValue(val);
            count++;
        }

        return count;
    }
}
