package com.trajets.galsync.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SettingsManager {
    private static final String PREFS_NAME = "galsync_entra_settings";

    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_TENANT_ID = "tenant_id";
    private static final String KEY_REDIRECT_URI = "redirect_uri";
    private static final String KEY_SECURITY_GROUP_ID = "security_group_id";
    private static final String KEY_FILTER_ATTRIBUTE = "filter_attribute";
    private static final String KEY_FILTER_VALUE = "filter_value";

    // Sync status keys
    private static final String KEY_SYNC_ENABLED = "sync_enabled";
    private static final String KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours";
    private static final String KEY_LAST_SYNC_SUCCESS_TIME = "last_sync_success_time";
    private static final String KEY_LAST_SYNC_SUCCESS_COUNT = "last_sync_success_count";
    private static final String KEY_LAST_SYNC_ERROR_TIME = "last_sync_error_time";
    private static final String KEY_LAST_SYNC_ERROR_MESSAGE = "last_sync_error_message";

    private static final int DEFAULT_SYNC_INTERVAL_HOURS = 24;

    private final SharedPreferences prefs;
    private final Context context;

    public SettingsManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
        return clientId != null && !clientId.trim().isEmpty()
                && tenantId != null && !tenantId.trim().isEmpty();
    }

    public boolean hasGroupFilter() {
        String groupId = getSecurityGroupId();
        return groupId != null && !groupId.trim().isEmpty();
    }

    public boolean hasAttributeFilter() {
        String attr = getFilterAttribute();
        String val = getFilterValue();
        return attr != null && !attr.trim().isEmpty()
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
        String redirectUri = getRedirectUri().trim();

        if (redirectUri.isEmpty()) {
            redirectUri = "msauth://com.trajets.galsync/LB%2FrljQKsdgZjjwJG1HL0VwoNhU%3D";
        }

        String authorityUrl = "https://login.microsoftonline.com/" + tenantId;

        String json = "{\n"
                + "  \"client_id\": \"" + clientId + "\",\n"
                + "  \"authorization_user_agent\": \"DEFAULT\",\n"
                + "  \"redirect_uri\": \"" + redirectUri + "\",\n"
                + "  \"account_mode\": \"SINGLE\",\n"
                + "  \"broker_redirect_uri_registered\": false,\n"
                + "  \"authorities\": [\n"
                + "    {\n"
                + "      \"type\": \"AAD\",\n"
                + "      \"authority_url\": \"" + authorityUrl + "\",\n"
                + "      \"default\": true\n"
                + "    }\n"
                + "  ]\n"
                + "}";

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
