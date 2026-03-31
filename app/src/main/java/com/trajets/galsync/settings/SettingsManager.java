package com.trajets.galsync.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class SettingsManager {
    private static final String TAG = "SettingsManager";
    private static final String PREFS_NAME = "galsync_entra_settings";

    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_TENANT_ID = "tenant_id";
    private static final String KEY_REDIRECT_URI = "redirect_uri";
    private static final String KEY_SECURITY_GROUP_ID = "security_group_id";
    private static final String KEY_FILTER_ATTRIBUTE = "filter_attribute";
    private static final String KEY_FILTER_VALUE = "filter_value";
    private static final String KEY_NESTED_GROUPS_ENABLED = "nested_groups_enabled";
    private static final String KEY_AUTO_COUNTRY_CODE = "auto_country_code";

    // Sync status keys
    private static final String KEY_SYNC_ENABLED = "sync_enabled";
    private static final String KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours";
    private static final String KEY_LAST_SYNC_SUCCESS_TIME = "last_sync_success_time";
    private static final String KEY_LAST_SYNC_SUCCESS_COUNT = "last_sync_success_count";
    private static final String KEY_LAST_SYNC_ERROR_TIME = "last_sync_error_time";
    private static final String KEY_LAST_SYNC_ERROR_MESSAGE = "last_sync_error_message";

    // Tracks whether the user has manually saved settings (or imported via QR/URL).
    // When false, bundled auth_config.json defaults are applied on first use.
    private static final String KEY_USER_HAS_CONFIGURED = "user_has_configured";

    private static final int DEFAULT_SYNC_INTERVAL_HOURS = 24;

    // UUID pattern for validating Client ID, Tenant ID, Group ID
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Allowed attribute names for OData filtering (alphanumeric only)
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private final SharedPreferences prefs;
    private final Context context;

    public SettingsManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- Validation ---

    public static boolean isValidUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value.trim()).matches();
    }

    public static boolean isValidAttributeName(String value) {
        return value != null && ATTRIBUTE_PATTERN.matcher(value.trim()).matches();
    }

    // --- Entra ID settings ---

    public String getClientId() {
        return prefs.getString(KEY_CLIENT_ID, "");
    }

    public void setClientId(String clientId) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId).apply();
    }

    public String getTenantId() {
        return prefs.getString(KEY_TENANT_ID, "");
    }

    public void setTenantId(String tenantId) {
        prefs.edit().putString(KEY_TENANT_ID, tenantId).apply();
    }

    public String getRedirectUri() {
        return prefs.getString(KEY_REDIRECT_URI, "");
    }

    public void setRedirectUri(String redirectUri) {
        prefs.edit().putString(KEY_REDIRECT_URI, redirectUri).apply();
    }

    public String getSecurityGroupId() {
        return prefs.getString(KEY_SECURITY_GROUP_ID, "");
    }

    public void setSecurityGroupId(String groupId) {
        prefs.edit().putString(KEY_SECURITY_GROUP_ID, groupId).apply();
    }

    public String getFilterAttribute() {
        return prefs.getString(KEY_FILTER_ATTRIBUTE, "");
    }

    public void setFilterAttribute(String attribute) {
        prefs.edit().putString(KEY_FILTER_ATTRIBUTE, attribute).apply();
    }

    public String getFilterValue() {
        return prefs.getString(KEY_FILTER_VALUE, "");
    }

    public void setFilterValue(String value) {
        prefs.edit().putString(KEY_FILTER_VALUE, value).apply();
    }

    public boolean isConfigured() {
        String clientId = getClientId();
        String tenantId = getTenantId();
        return isValidUuid(clientId) && isValidUuid(tenantId);
    }

    public boolean hasGroupFilter() {
        List<String> ids = getSecurityGroupIds();
        return !ids.isEmpty();
    }

    /**
     * Parse the security group ID field as a comma-separated list of UUIDs.
     * Returns only the entries that are valid UUIDs.
     */
    public List<String> getSecurityGroupIds() {
        String raw = getSecurityGroupId().trim();
        List<String> ids = new ArrayList<>();
        if (raw.isEmpty()) return ids;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (isValidUuid(trimmed)) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    /**
     * Validate that a comma-separated string contains only valid UUIDs.
     */
    public static boolean isValidGroupIdList(String value) {
        if (value == null || value.trim().isEmpty()) return true; // empty is valid (optional)
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!isValidUuid(trimmed)) return false;
        }
        return true;
    }

    public boolean isNestedGroupsEnabled() {
        return prefs.getBoolean(KEY_NESTED_GROUPS_ENABLED, false);
    }

    public void setNestedGroupsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NESTED_GROUPS_ENABLED, enabled).apply();
    }

    public boolean isAutoCountryCodeEnabled() {
        return prefs.getBoolean(KEY_AUTO_COUNTRY_CODE, true);
    }

    public void setAutoCountryCodeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_COUNTRY_CODE, enabled).apply();
    }

    public boolean hasAttributeFilter() {
        String attr = getFilterAttribute();
        String val = getFilterValue();
        return isValidAttributeName(attr)
                && val != null && !val.trim().isEmpty();
    }

    // --- Sync status and interval ---

    public boolean isSyncEnabled() {
        return prefs.getBoolean(KEY_SYNC_ENABLED, true);
    }

    public void setSyncEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply();
    }

    public int getSyncIntervalHours() {
        return prefs.getInt(KEY_SYNC_INTERVAL_HOURS, DEFAULT_SYNC_INTERVAL_HOURS);
    }

    public void setSyncIntervalHours(int hours) {
        prefs.edit().putInt(KEY_SYNC_INTERVAL_HOURS, hours).apply();
    }

    public void recordSyncSuccess(int contactCount) {
        prefs.edit()
                .putLong(KEY_LAST_SYNC_SUCCESS_TIME, System.currentTimeMillis())
                .putInt(KEY_LAST_SYNC_SUCCESS_COUNT, contactCount)
                .apply();
    }

    public void recordSyncError(String errorMessage) {
        prefs.edit()
                .putLong(KEY_LAST_SYNC_ERROR_TIME, System.currentTimeMillis())
                .putString(KEY_LAST_SYNC_ERROR_MESSAGE, errorMessage)
                .apply();
    }

    public long getLastSyncSuccessTime() {
        return prefs.getLong(KEY_LAST_SYNC_SUCCESS_TIME, 0);
    }

    public int getLastSyncSuccessCount() {
        return prefs.getInt(KEY_LAST_SYNC_SUCCESS_COUNT, 0);
    }

    public long getLastSyncErrorTime() {
        return prefs.getLong(KEY_LAST_SYNC_ERROR_TIME, 0);
    }

    public String getLastSyncErrorMessage() {
        return prefs.getString(KEY_LAST_SYNC_ERROR_MESSAGE, "");
    }

    public String formatTimestamp(long timestamp) {
        if (timestamp == 0) return null;
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    // --- Bundled config auto-fill ---

    public boolean hasUserConfigured() {
        return prefs.getBoolean(KEY_USER_HAS_CONFIGURED, false);
    }

    public void markUserConfigured() {
        prefs.edit().putBoolean(KEY_USER_HAS_CONFIGURED, true).apply();
    }

    /**
     * On first launch (before the user has saved settings manually), read the bundled
     * auth_config.json from res/raw and populate settings from it.
     *
     * If the bundled file still contains "PLACEHOLDER" values, nothing is applied —
     * this means the admin has not customized the APK and the user must configure manually.
     *
     * Call this once from MainActivity.onCreate().
     */
    public void loadDefaultsIfNeeded(int rawResourceId) {
        if (hasUserConfigured()) {
            Log.d(TAG, "loadDefaults: skipped — user has already configured settings");
            return;
        }

        try {
            InputStream is = context.getResources().openRawResource(rawResourceId);
            InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            String clientId = getJsonString(obj, "client_id");
            String tenantId = getJsonString(obj, "tenant_id");

            Log.d(TAG, "loadDefaults: client_id='" + clientId + "', tenant_id='" + tenantId + "'");

            // If the bundled config still has placeholder values, skip auto-fill.
            if (clientId == null || tenantId == null
                    || "PLACEHOLDER".equalsIgnoreCase(clientId)
                    || "PLACEHOLDER".equalsIgnoreCase(tenantId)) {
                Log.d(TAG, "loadDefaults: skipped — PLACEHOLDER values detected");
                return;
            }

            // Extract tenant ID from authority_url as fallback
            if (tenantId.isEmpty() && obj.has("authorities")) {
                JsonArray authorities = obj.getAsJsonArray("authorities");
                if (authorities.size() > 0) {
                    JsonObject auth = authorities.get(0).getAsJsonObject();
                    String url = getJsonString(auth, "authority_url");
                    if (url != null && url.contains("microsoftonline.com/")) {
                        String[] parts = url.split("microsoftonline.com/");
                        if (parts.length > 1 && !parts[1].isEmpty()) {
                            tenantId = parts[1].replace("/", "");
                            Log.d(TAG, "loadDefaults: extracted tenant_id from authority_url: " + tenantId);
                        }
                    }
                }
            }

            // Apply bundled values to settings
            if (isValidUuid(clientId)) {
                setClientId(clientId);
                Log.d(TAG, "loadDefaults: set client_id");
            } else {
                Log.w(TAG, "loadDefaults: client_id is not a valid UUID, skipped");
            }
            if (isValidUuid(tenantId)) {
                setTenantId(tenantId);
                Log.d(TAG, "loadDefaults: set tenant_id");
            } else {
                Log.w(TAG, "loadDefaults: tenant_id is not a valid UUID, skipped");
            }

            String redirectUri = getJsonString(obj, "redirect_uri");
            if (redirectUri != null && !redirectUri.isEmpty()
                    && !"PLACEHOLDER".equalsIgnoreCase(redirectUri)
                    && !redirectUri.contains("PLACEHOLDER")) {
                setRedirectUri(redirectUri);
                Log.d(TAG, "loadDefaults: set redirect_uri");
            }

            // GalSync-specific fields (prefixed with galsync_)
            String groupId = getJsonString(obj, "galsync_security_group_id");
            if (groupId != null && !groupId.isEmpty()) {
                setSecurityGroupId(groupId);
                Log.d(TAG, "loadDefaults: set security_group_id");
            }

            String filterAttr = getJsonString(obj, "galsync_filter_attribute");
            if (filterAttr != null && !filterAttr.isEmpty()) {
                setFilterAttribute(filterAttr);
                Log.d(TAG, "loadDefaults: set filter_attribute");
            }

            String filterVal = getJsonString(obj, "galsync_filter_value");
            if (filterVal != null && !filterVal.isEmpty()) {
                setFilterValue(filterVal);
                Log.d(TAG, "loadDefaults: set filter_value");
            }

            if (obj.has("galsync_nested_groups") && !obj.get("galsync_nested_groups").isJsonNull()) {
                try {
                    boolean nested = obj.get("galsync_nested_groups").getAsBoolean();
                    setNestedGroupsEnabled(nested);
                    Log.d(TAG, "loadDefaults: set nested_groups=" + nested);
                } catch (Exception e) {
                    Log.w(TAG, "loadDefaults: galsync_nested_groups is not a boolean");
                }
            }

            if (isConfigured()) {
                generateAuthConfig();
                Log.d(TAG, "loadDefaults: configuration applied and auth config generated");
            } else {
                Log.w(TAG, "loadDefaults: client_id/tenant_id not valid — settings not fully configured");
            }

        } catch (Exception e) {
            Log.e(TAG, "loadDefaults: error reading bundled config", e);
        }
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read the redirect_uri that matches the BrowserTabActivity intent-filter
     * declared in the AndroidManifest. This ensures the MSAL config always
     * matches the manifest, regardless of what the user entered in settings.
     */
    private String getRedirectUriFromManifest() {
        try {
            // Query for activities that handle msauth:// intents
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setData(Uri.parse("msauth://placeholder"));

            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(intent,
                    PackageManager.GET_RESOLVED_FILTER);

            for (ResolveInfo info : activities) {
                if (info.activityInfo != null
                        && "com.microsoft.identity.client.BrowserTabActivity"
                                .equals(info.activityInfo.name)) {
                    // Found it — reconstruct the redirect_uri from the intent-filter
                    if (info.filter != null && info.filter.countDataAuthorities() > 0
                            && info.filter.countDataPaths() > 0) {
                        String scheme = "msauth";
                        String host = info.filter.getDataAuthority(0).getHost();
                        String path = info.filter.getDataPath(0).getPath();
                        // URL-encode the path (especially the '=' at the end)
                        String encodedPath = Uri.encode(path, "/");
                        String uri = scheme + "://" + host + encodedPath;
                        Log.d(TAG, "Redirect URI from manifest: " + uri);
                        return uri;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read redirect_uri from manifest", e);
        }
        return null;
    }

    /**
     * Generate the MSAL auth_config.json dynamically from settings
     * and write it to internal storage.
     */
    public File generateAuthConfig() {
        if (!isConfigured()) {
            return null;
        }

        String clientId = getClientId().trim();
        String tenantId = getTenantId().trim();

        // Always use the redirect_uri from the manifest to guarantee it matches
        // the BrowserTabActivity intent-filter. User-provided redirect_uri is
        // stored in settings but NOT used for MSAL config generation, because
        // a mismatch causes a fatal MsalClientException at init time.
        String redirectUri = getRedirectUriFromManifest();
        if (redirectUri == null || redirectUri.isEmpty()) {
            // Fallback: use user-provided or hardcoded default
            redirectUri = getRedirectUri().trim();
            if (redirectUri.isEmpty()) {
                redirectUri = "msauth://com.trajets.galsync2/m45g9VrDROQ8Bqy9zxEAICN2KKA%3D";
            }
            Log.w(TAG, "Could not read manifest redirect_uri, using fallback: " + redirectUri);
        }

        // Use Gson to safely build the JSON and avoid injection
        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
        config.addProperty("client_id", clientId);
        config.addProperty("authorization_user_agent", "DEFAULT");
        config.addProperty("redirect_uri", redirectUri);
        config.addProperty("account_mode", "SINGLE");
        config.addProperty("broker_redirect_uri_registered", false);

        com.google.gson.JsonObject authority = new com.google.gson.JsonObject();
        authority.addProperty("type", "AAD");
        authority.addProperty("authority_url", "https://login.microsoftonline.com/" + tenantId);
        authority.addProperty("default", true);

        com.google.gson.JsonArray authorities = new com.google.gson.JsonArray();
        authorities.add(authority);
        config.add("authorities", authorities);

        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config);

        try {
            File configFile = new File(context.getFilesDir(), "auth_config_dynamic.json");
            FileWriter writer = new FileWriter(configFile);
            writer.write(json);
            writer.close();
            return configFile;
        } catch (Exception e) {
            return null;
        }
    }
}
