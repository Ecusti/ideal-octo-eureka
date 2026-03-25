package com.trajets.galsync.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.trajets.galsync.R;

public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settingsManager;

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
    private TextView tvLastError;
    private Button btnSave;

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
        tvLastError = findViewById(R.id.tv_last_error);
        btnSave = findViewById(R.id.btn_save_settings);

        // Setup interval spinner
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
    }

    private void loadSettings() {
        etClientId.setText(settingsManager.getClientId());
        etTenantId.setText(settingsManager.getTenantId());
        etRedirectUri.setText(settingsManager.getRedirectUri());
        etSecurityGroupId.setText(settingsManager.getSecurityGroupId());
        etFilterAttribute.setText(settingsManager.getFilterAttribute());
        etFilterValue.setText(settingsManager.getFilterValue());

        switchSyncEnabled.setChecked(settingsManager.isSyncEnabled());

        // Select the current interval in the spinner
        int currentInterval = settingsManager.getSyncIntervalHours();
        int selectedIndex = 5; // Default to "Tous les jours" (24h)
        for (int i = 0; i < INTERVAL_HOURS.length; i++) {
            if (INTERVAL_HOURS[i] == currentInterval) {
                selectedIndex = i;
                break;
            }
        }
        spinnerSyncInterval.setSelection(selectedIndex);
    }

    private void loadSyncStatus() {
        // Sync enabled/disabled status
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

        // Last error
        long lastErrorTime = settingsManager.getLastSyncErrorTime();
        if (lastErrorTime > 0) {
            String date = settingsManager.formatTimestamp(lastErrorTime);
            String message = settingsManager.getLastSyncErrorMessage();
            tvLastError.setText(getString(R.string.settings_last_error_format, date, message));
        } else {
            tvLastError.setText(R.string.settings_last_error_none);
        }
    }

    private void saveSettings() {
        String clientId = etClientId.getText().toString().trim();
        String tenantId = etTenantId.getText().toString().trim();

        if (clientId.isEmpty() || tenantId.isEmpty()) {
            Toast.makeText(this, R.string.settings_error_required, Toast.LENGTH_LONG).show();
            return;
        }

        settingsManager.setClientId(clientId);
        settingsManager.setTenantId(tenantId);
        settingsManager.setRedirectUri(etRedirectUri.getText().toString().trim());
        settingsManager.setSecurityGroupId(etSecurityGroupId.getText().toString().trim());
        settingsManager.setFilterAttribute(etFilterAttribute.getText().toString().trim());
        settingsManager.setFilterValue(etFilterValue.getText().toString().trim());

        settingsManager.setSyncEnabled(switchSyncEnabled.isChecked());
        int selectedIndex = spinnerSyncInterval.getSelectedItemPosition();
        if (selectedIndex >= 0 && selectedIndex < INTERVAL_HOURS.length) {
            settingsManager.setSyncIntervalHours(INTERVAL_HOURS[selectedIndex]);
        }

        // Generate the dynamic auth config file
        settingsManager.generateAuthConfig();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
