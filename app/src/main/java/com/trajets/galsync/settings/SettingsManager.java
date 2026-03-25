package com.trajets.galsync.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;

public class SettingsManager {
    private static final String PREFS_NAME = "galsync_entra_settings";

    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_TENANT_ID = "tenant_id";
    private static final String KEY_REDIRECT_URI = "redirect_uri";
    private static final String KEY_SECURITY_GROUP_ID = "security_group_id";
    private static final String KEY_FILTER_ATTRIBUTE = "filter_attribute";
    private static final String KEY_FILTER_VALUE = "filter_value";

    private final SharedPreferences prefs;
    private final Context context;

    public SettingsManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

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

    /**
     * Generate the MSAL auth_config.json dynamically from settings
     * and write it to internal storage.
     *
     * @return the File pointing to the generated config, or null if settings are incomplete
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
