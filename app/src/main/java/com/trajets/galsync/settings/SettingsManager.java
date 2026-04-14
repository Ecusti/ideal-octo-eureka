package com.trajets.galsync.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;

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

    // Auth strategy constants for generateAuthConfig(int)
    public static final int AUTH_STRATEGY_BROKER = 0;
    public static final int AUTH_STRATEGY_BROWSER = 1;
    public static final int AUTH_STRATEGY_DEFAULT_NO_BROKER = 2;

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

    /**
     * Detect whether the app is running inside an Android work profile
     * (managed by Intune, Knox, etc.).
     *
     * This is used to skip broker auth (Microsoft Authenticator can't communicate
     * across profile boundaries) and to prefer Edge as the auth browser.
     */
    public static boolean isRunningInWorkProfile(Context context) {
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: definitive check
                return um.isManagedProfile();
            }
            // API 26-29: non-system user is typically a work/managed profile
            return !um.isSystemUser();
        } catch (Exception e) {
            Log.w(TAG, "Could not detect work profile", e);
            return false;
        }
    }

    /**
     * Check if Microsoft Edge is installed (work profile browser preference).
     */
    public boolean isEdgeInstalled() {
        try {
            context.getPackageManager().getPackageInfo("com.microsoft.emmx", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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
        if (hasUserConfigured() && isConfigured()) {
            Log.d(TAG, "loadDefaults: skipped — user has already configured valid settings");
            return;
        }
        if (hasUserConfigured() && !isConfigured()) {
            Log.d(TAG, "loadDefaults: user_has_configured flag was set but settings are empty — reloading defaults");
        }

        try {
            InputStream is = context.getResources().openRawResource(rawResourceId);
            InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            String clientId = getJsonString(obj, "client_id");
            String tenantId = getJsonString(obj, "tenant_id");

            // Extract tenant ID from authority_url if not provided as a separate field.
            // MSAL configs typically embed the tenant in the authority URL rather than
            // as a standalone "tenant_id" field.
            if ((tenantId == null || tenantId.isEmpty() || "PLACEHOLDER".equalsIgnoreCase(tenantId))
                    && obj.has("authorities")) {
                JsonArray authorities = obj.getAsJsonArray("authorities");
                if (authorities.size() > 0) {
                    JsonObject auth = authorities.get(0).getAsJsonObject();
                    String url = getJsonString(auth, "authority_url");
                    if (url != null && url.contains("microsoftonline.com/")) {
                        String[] parts = url.split("microsoftonline.com/");
                        if (parts.length > 1 && !parts[1].isEmpty()) {
                            String extracted = parts[1].replace("/", "");
                            if (!"common".equalsIgnoreCase(extracted)
                                    && !"organizations".equalsIgnoreCase(extracted)
                                    && !"consumers".equalsIgnoreCase(extracted)) {
                                tenantId = extracted;
                                Log.d(TAG, "loadDefaults: extracted tenant_id from authority_url: " + tenantId);
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "loadDefaults: client_id='" + clientId + "', tenant_id='" + tenantId + "'");

            // If the bundled config still has placeholder values, skip auto-fill.
            if (clientId == null || tenantId == null
                    || "PLACEHOLDER".equalsIgnoreCase(clientId)
                    || "PLACEHOLDER".equalsIgnoreCase(tenantId)) {
                Log.d(TAG, "loadDefaults: skipped — PLACEHOLDER values detected");
                return;
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
     * declared in the AndroidManifest.
     *
     * Uses multiple strategies to work across all device types including
     * Samsung Knox work profiles where queryIntentActivities() may fail.
     */
    private String getRedirectUriFromManifest() {
        // Strategy 1: Read BrowserTabActivity intent-filter directly from our own package
        try {
            PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);

            if (pkgInfo.activities != null) {
                for (android.content.pm.ActivityInfo actInfo : pkgInfo.activities) {
                    if ("com.microsoft.identity.client.BrowserTabActivity".equals(actInfo.name)) {
                        // Found the BrowserTabActivity — now query its intent-filter
                        return getRedirectUriViaIntentQuery(pm);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Strategy 1 (getPackageInfo) failed", e);
        }

        // Strategy 2: Query intent activities directly
        String uri = getRedirectUriViaIntentQuery(context.getPackageManager());
        if (uri != null) return uri;

        // Strategy 3: Build from known package name and signature hash
        // This matches the BrowserTabActivity intent-filter in AndroidManifest.xml:
        //   host="com.trajets.galsync2" path="/m45g9VrDROQ8Bqy9zxEAICN2KKA="
        String packageName = context.getPackageName();
        if ("com.trajets.galsync2".equals(packageName)) {
            String fallback = "msauth://com.trajets.galsync2/m45g9VrDROQ8Bqy9zxEAICN2KKA%3D";
            Log.d(TAG, "Redirect URI from hardcoded fallback: " + fallback);
            return fallback;
        }

        Log.w(TAG, "Could not determine redirect_uri from any strategy");
        return null;
    }

    private String getRedirectUriViaIntentQuery(PackageManager pm) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setData(Uri.parse("msauth://placeholder"));

            List<ResolveInfo> activities = pm.queryIntentActivities(intent,
                    PackageManager.GET_RESOLVED_FILTER);

            for (ResolveInfo info : activities) {
                if (info.activityInfo != null
                        && "com.microsoft.identity.client.BrowserTabActivity"
                                .equals(info.activityInfo.name)) {
                    if (info.filter != null && info.filter.countDataAuthorities() > 0
                            && info.filter.countDataPaths() > 0) {
                        String scheme = "msauth";
                        String host = info.filter.getDataAuthority(0).getHost();
                        String path = info.filter.getDataPath(0).getPath();
                        String encodedPath = Uri.encode(path, "/");
                        String uri = scheme + "://" + host + encodedPath;
                        Log.d(TAG, "Redirect URI from intent query: " + uri);
                        return uri;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Intent query for redirect_uri failed", e);
        }
        return null;
    }

    /**
     * Generate the MSAL auth_config.json dynamically from settings
     * and write it to internal storage. Uses broker strategy by default.
     */
    public File generateAuthConfig() {
        return generateAuthConfig(AUTH_STRATEGY_BROKER);
    }

    /**
     * Legacy overload: true = BROKER, false = BROWSER.
     */
    public File generateAuthConfig(boolean withBroker) {
        return generateAuthConfig(withBroker ? AUTH_STRATEGY_BROKER : AUTH_STRATEGY_BROWSER);
    }

    /**
     * Generate the MSAL auth_config.json for the given auth strategy.
     *
     * @param strategy one of AUTH_STRATEGY_BROKER, AUTH_STRATEGY_BROWSER,
     *                 or AUTH_STRATEGY_DEFAULT_NO_BROKER
     */
    public File generateAuthConfig(int strategy) {
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
        config.addProperty("redirect_uri", redirectUri);
        config.addProperty("account_mode", "SINGLE");

        String strategyName;
        switch (strategy) {
            case AUTH_STRATEGY_BROKER:
                // Broker mode: uses Authenticator app for phishing-resistant MFA
                config.addProperty("authorization_user_agent", "DEFAULT");
                config.addProperty("broker_redirect_uri_registered", true);
                strategyName = "BROKER";
                break;

            case AUTH_STRATEGY_BROWSER:
                // Browser-only mode via Custom Tabs
                config.addProperty("authorization_user_agent", "BROWSER");
                config.addProperty("broker_redirect_uri_registered", false);
                strategyName = "BROWSER";
                break;

            case AUTH_STRATEGY_DEFAULT_NO_BROKER:
            default:
                // Minimal mode: let MSAL pick (DEFAULT) but without broker redirect.
                // This allows MSAL to use its own fallback logic including regular
                // browser intents (not just Custom Tabs).
                config.addProperty("authorization_user_agent", "DEFAULT");
                config.addProperty("broker_redirect_uri_registered", false);
                strategyName = "DEFAULT_NO_BROKER";
                break;
        }

        com.google.gson.JsonObject authority = new com.google.gson.JsonObject();
        authority.addProperty("type", "AAD");
        authority.addProperty("authority_url", "https://login.microsoftonline.com/" + tenantId);
        authority.addProperty("default", true);

        com.google.gson.JsonArray authorities = new com.google.gson.JsonArray();
        authorities.add(authority);
        config.add("authorities", authorities);

        // In work profiles, add browser_safelist to prefer Edge, then Chrome,
        // then Samsung Internet. This ensures MSAL finds an appropriate browser
        // even if default browser discovery is restricted by Knox.
        if (strategy != AUTH_STRATEGY_BROKER && isRunningInWorkProfile(context)) {
            com.google.gson.JsonArray browserSafelist = buildWorkProfileBrowserSafelist();
            if (browserSafelist.size() > 0) {
                config.add("browser_safelist", browserSafelist);
                Log.d(TAG, "Added browser_safelist for work profile (" + browserSafelist.size() + " entries)");
            }
        }

        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config);

        Log.d(TAG, "generateAuthConfig: strategy=" + strategyName);

        try {
            File configFile = new File(context.getFilesDir(), "auth_config_dynamic.json");
            FileWriter writer = new FileWriter(configFile);
            writer.write(json);
            writer.close();
            return configFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write auth config file", e);
            return null;
        }
    }

    /**
     * Build the MSAL browser_safelist for work profiles.
     * Order: Edge → Chrome → Samsung Internet.
     * Only includes browsers that are actually installed.
     */
    private com.google.gson.JsonArray buildWorkProfileBrowserSafelist() {
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        PackageManager pm = context.getPackageManager();

        // Edge (preferred in managed/work environments)
        addBrowserIfInstalled(list, pm, "com.microsoft.emmx", "45.0");
        // Chrome
        addBrowserIfInstalled(list, pm, "com.android.chrome", "45.0");
        // Samsung Internet
        addBrowserIfInstalled(list, pm, "com.sec.android.app.sbrowser", "1.0");

        return list;
    }

    private void addBrowserIfInstalled(com.google.gson.JsonArray list, PackageManager pm,
                                        String packageName, String minVersion) {
        try {
            android.content.pm.PackageInfo info = pm.getPackageInfo(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info != null) {
                com.google.gson.JsonObject browser = new com.google.gson.JsonObject();
                browser.addProperty("browser_package_name", packageName);
                browser.addProperty("browser_use_customTab", true);
                browser.addProperty("browser_version_lower_bound", minVersion);

                // Compute the SHA-256 signature hash from the installed browser's
                // signing certificate so MSAL accepts it.
                com.google.gson.JsonArray hashes = new com.google.gson.JsonArray();
                String hash = getSignatureHash(info);
                if (hash != null) {
                    hashes.add(hash);
                }
                browser.add("browser_signature_hashes", hashes);

                list.add(browser);
                Log.d(TAG, "Browser safelist: added " + packageName
                        + (hash != null ? " (hash=" + hash.substring(0, 8) + "...)" : " (no hash)"));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Browser safelist: " + packageName + " not installed");
        } catch (Exception e) {
            Log.w(TAG, "Browser safelist: error checking " + packageName, e);
        }
    }

    /**
     * Compute the Base64-encoded SHA-256 hash of a package's signing certificate.
     * This is the format MSAL expects in browser_signature_hashes.
     */
    private String getSignatureHash(android.content.pm.PackageInfo packageInfo) {
        try {
            android.content.pm.Signature[] signatures = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.SigningInfo signingInfo = packageInfo.signingInfo;
                if (signingInfo != null) {
                    signatures = signingInfo.getApkContentsSigners();
                }
            }
            if (signatures == null) {
                // Fallback for older behavior (shouldn't happen with minSdk 26)
                return null;
            }
            if (signatures.length > 0) {
                byte[] certBytes = signatures[0].toByteArray();
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(certBytes);
                return android.util.Base64.encodeToString(digest,
                        android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not compute signature hash", e);
        }
        return null;
    }
}
