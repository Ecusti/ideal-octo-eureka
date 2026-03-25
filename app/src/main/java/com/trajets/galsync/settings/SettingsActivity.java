package com.trajets.galsync.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.trajets.galsync.R;

public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settingsManager;

    private EditText etClientId;
    private EditText etTenantId;
    private EditText etRedirectUri;
    private EditText etSecurityGroupId;
    private EditText etFilterAttribute;
    private EditText etFilterValue;
    private Button btnSave;

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
    }

    private void initializeViews() {
        etClientId = findViewById(R.id.et_client_id);
        etTenantId = findViewById(R.id.et_tenant_id);
        etRedirectUri = findViewById(R.id.et_redirect_uri);
        etSecurityGroupId = findViewById(R.id.et_security_group_id);
        etFilterAttribute = findViewById(R.id.et_filter_attribute);
        etFilterValue = findViewById(R.id.et_filter_value);
        btnSave = findViewById(R.id.btn_save_settings);

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
