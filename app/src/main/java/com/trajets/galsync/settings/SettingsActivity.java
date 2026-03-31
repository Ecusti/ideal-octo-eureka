package com.trajets.galsync.settings;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.trajets.galsync.R;

public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settingsManager;
    private ConfigImporter configImporter;

    private EditText etClientId;
    private EditText etTenantId;
    private EditText etRedirectUri;
    private EditText etSecurityGroupId;
    private EditText etFilterAttribute;
    private EditText etFilterValue;
    private SwitchCompat switchSyncEnabled;
    private Spinner spinnerSyncInterval;
    private TextView tvSyncStatus;
    private TextView tvLastSuccess;
    private TextView tvLastStatus;
    private Button btnSave;
    private CheckBox cbNestedGroups;
    private Button btnImportQr;
    private Button btnImportUrl;

    // Interval options in hours, matching the spinner entries
    private static final int[] INTERVAL_HOURS = {1, 2, 4, 6, 12, 24, 48};
    private static final String[] INTERVAL_LABELS = {
            "Toutes les heures",
            "Toutes les 2 heures",
            "Toutes les 4 heures",
            "Toutes les 6 heures",
            "Toutes les 12 heures",
            "Tous les jours",
            "Tous les 2 jours"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
        }

        settingsManager = new SettingsManager(this);
        configImporter = new ConfigImporter();
        initializeViews();
        loadSettings();
        loadSyncStatus();
    }

    private void initializeViews() {
        etClientId = findViewById(R.id.et_client_id);
        etTenantId = findViewById(R.id.et_tenant_id);
        etRedirectUri = findViewById(R.id.et_redirect_uri);
        etSecurityGroupId = findViewById(R.id.et_security_group_id);
        etFilterAttribute = findViewById(R.id.et_filter_attribute);
        etFilterValue = findViewById(R.id.et_filter_value);
        switchSyncEnabled = findViewById(R.id.switch_sync_enabled);
        spinnerSyncInterval = findViewById(R.id.spinner_sync_interval);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        tvLastSuccess = findViewById(R.id.tv_last_success);
        tvLastStatus = findViewById(R.id.tv_last_status);
        btnSave = findViewById(R.id.btn_save_settings);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, INTERVAL_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSyncInterval.setAdapter(adapter);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });

        cbNestedGroups = findViewById(R.id.cb_nested_groups);

        btnImportQr = findViewById(R.id.btn_import_qr);
        btnImportUrl = findViewById(R.id.btn_import_url);

        btnImportQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanQrCode();
            }
        });

        btnImportUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUrlImportDialog();
            }
        });
    }

    private void loadSettings() {
        etClientId.setText(settingsManager.getClientId());
        etTenantId.setText(settingsManager.getTenantId());
        etRedirectUri.setText(settingsManager.getRedirectUri());
        etSecurityGroupId.setText(settingsManager.getSecurityGroupId());
        cbNestedGroups.setChecked(settingsManager.isNestedGroupsEnabled());
        etFilterAttribute.setText(settingsManager.getFilterAttribute());
        etFilterValue.setText(settingsManager.getFilterValue());

        switchSyncEnabled.setChecked(settingsManager.isSyncEnabled());

        int currentInterval = settingsManager.getSyncIntervalHours();
        int selectedIndex = 5; // Default to 24h
        for (int i = 0; i < INTERVAL_HOURS.length; i++) {
            if (INTERVAL_HOURS[i] == currentInterval) {
                selectedIndex = i;
                break;
            }
        }
        spinnerSyncInterval.setSelection(selectedIndex);
    }

    private void loadSyncStatus() {
        boolean enabled = settingsManager.isSyncEnabled();
        tvSyncStatus.setText(enabled
                ? getString(R.string.settings_sync_status_enabled)
                : getString(R.string.settings_sync_status_disabled));

        // Last successful sync
        long lastSuccessTime = settingsManager.getLastSyncSuccessTime();
        if (lastSuccessTime > 0) {
            String date = settingsManager.formatTimestamp(lastSuccessTime);
            int count = settingsManager.getLastSyncSuccessCount();
            tvLastSuccess.setText(getString(R.string.settings_last_success_format, date, count));
        } else {
            tvLastSuccess.setText(R.string.settings_last_success_never);
        }

        // Last status: show whichever happened most recently (success or error)
        long lastErrorTime = settingsManager.getLastSyncErrorTime();
        if (lastSuccessTime == 0 && lastErrorTime == 0) {
            tvLastStatus.setText(R.string.settings_last_status_none);
        } else if (lastSuccessTime >= lastErrorTime) {
            // Last action was a success
            String date = settingsManager.formatTimestamp(lastSuccessTime);
            int count = settingsManager.getLastSyncSuccessCount();
            tvLastStatus.setText(getString(R.string.settings_last_status_success, date, count));
        } else {
            // Last action was an error
            String date = settingsManager.formatTimestamp(lastErrorTime);
            String message = settingsManager.getLastSyncErrorMessage();
            tvLastStatus.setText(getString(R.string.settings_last_status_error, date, message));
        }
    }

    private void saveSettings() {
        String clientId = etClientId.getText().toString().trim();
        String tenantId = etTenantId.getText().toString().trim();

        // Validate required fields
        if (clientId.isEmpty() || tenantId.isEmpty()) {
            Toast.makeText(this, R.string.settings_error_required, Toast.LENGTH_LONG).show();
            return;
        }

        // Validate UUID format for Client ID and Tenant ID
        if (!SettingsManager.isValidUuid(clientId) || !SettingsManager.isValidUuid(tenantId)) {
            Toast.makeText(this, R.string.settings_validation_uuid, Toast.LENGTH_LONG).show();
            return;
        }

        // Validate optional security group ID(s) — comma-separated UUIDs
        String groupId = etSecurityGroupId.getText().toString().trim();
        if (!groupId.isEmpty() && !SettingsManager.isValidGroupIdList(groupId)) {
            Toast.makeText(this, R.string.settings_validation_group_ids, Toast.LENGTH_LONG).show();
            return;
        }

        // Validate optional attribute name
        String filterAttr = etFilterAttribute.getText().toString().trim();
        if (!filterAttr.isEmpty() && !SettingsManager.isValidAttributeName(filterAttr)) {
            Toast.makeText(this, R.string.settings_validation_attribute, Toast.LENGTH_LONG).show();
            return;
        }

        settingsManager.setClientId(clientId);
        settingsManager.setTenantId(tenantId);
        settingsManager.setRedirectUri(etRedirectUri.getText().toString().trim());
        settingsManager.setSecurityGroupId(groupId);
        settingsManager.setNestedGroupsEnabled(cbNestedGroups.isChecked());
        settingsManager.setFilterAttribute(filterAttr);
        settingsManager.setFilterValue(etFilterValue.getText().toString().trim());

        settingsManager.setSyncEnabled(switchSyncEnabled.isChecked());
        int selectedIndex = spinnerSyncInterval.getSelectedItemPosition();
        if (selectedIndex >= 0 && selectedIndex < INTERVAL_HOURS.length) {
            settingsManager.setSyncIntervalHours(INTERVAL_HOURS[selectedIndex]);
        }

        settingsManager.markUserConfigured();
        settingsManager.generateAuthConfig();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                String contents = result.getContents();
                if (contents != null && !contents.isEmpty()) {
                    handleScannedData(contents);
                }
            });

    private void scanQrCode() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.settings_import_qr_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        qrScanLauncher.launch(options);
    }

    private void handleScannedData(String data) {
        String trimmed = data.trim();

        // If the QR code contains a URL, fetch the config from it
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            Toast.makeText(this, R.string.settings_import_loading, Toast.LENGTH_SHORT).show();
            configImporter.importFromUrl(trimmed, importCallback);
        } else {
            // Otherwise treat as inline JSON
            configImporter.importFromJson(trimmed, importCallback);
        }
    }

    private void showUrlImportDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.settings_import_url_hint);
        input.setPadding(48, 32, 48, 16);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_import_url_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        Toast.makeText(this, R.string.settings_import_loading, Toast.LENGTH_SHORT).show();
                        configImporter.importFromUrl(url, importCallback);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final ConfigImporter.ImportCallback importCallback = new ConfigImporter.ImportCallback() {
        @Override
        public void onSuccess(ConfigImporter.ImportedConfig config) {
            // Populate each EditText from the imported config.
            // null = field was absent in the JSON → clear the field.
            // non-null (even empty) = field was present → show the raw value.
            // Validation happens only when the user taps Save.
            etClientId.setText(config.clientId != null ? config.clientId : "");
            etTenantId.setText(config.tenantId != null ? config.tenantId : "");
            etRedirectUri.setText(config.redirectUri != null ? config.redirectUri : "");
            etSecurityGroupId.setText(config.securityGroupId != null ? config.securityGroupId : "");
            if (config.nestedGroups != null) {
                cbNestedGroups.setChecked(config.nestedGroups);
            } else {
                cbNestedGroups.setChecked(false);
            }
            etFilterAttribute.setText(config.filterAttribute != null ? config.filterAttribute : "");
            etFilterValue.setText(config.filterValue != null ? config.filterValue : "");

            Toast.makeText(SettingsActivity.this,
                    getString(R.string.settings_import_success, config.fieldCount()),
                    Toast.LENGTH_LONG).show();
        }

        @Override
        public void onError(String message) {
            Toast.makeText(SettingsActivity.this,
                    getString(R.string.settings_import_error, message),
                    Toast.LENGTH_LONG).show();
        }
    };

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
