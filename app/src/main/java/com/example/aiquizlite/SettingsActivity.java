package com.example.aiquizlite;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private TextInputLayout baseUrlInputLayout;
    private TextInputLayout apiKeyInputLayout;
    private TextInputLayout modelInputLayout;
    private TextInputEditText baseUrlInput;
    private TextInputEditText apiKeyInput;
    private TextInputEditText modelInput;
    private View configSummaryCard;
    private View configGuideContainer;
    private TextView configuredModelText;
    private TextView configuredEndpointText;
    private TextView connectionStatusText;
    private TextView apiKeyHelpText;
    private MaterialButton testConnectionButton;
    private MaterialButton saveConfigButton;
    private MaterialButton clearApiKeyButton;
    private MaterialButton clearAiCacheButton;
    private CircularProgressIndicator settingsProgress;

    private ApiConfigStore configStore;
    private AiExplanationCache explanationCache;
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastSuccessfulDraftFingerprint = "";
    private boolean requestRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        setContentView(R.layout.activity_settings);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);

        configStore = new ApiConfigStore(this);
        explanationCache = new AiExplanationCache(this);
        bindViews();
        bindActions();
        renderState(false);
    }

    private void bindViews() {
        baseUrlInputLayout = findViewById(R.id.baseUrlInputLayout);
        apiKeyInputLayout = findViewById(R.id.apiKeyInputLayout);
        modelInputLayout = findViewById(R.id.modelInputLayout);
        baseUrlInput = findViewById(R.id.baseUrlInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        modelInput = findViewById(R.id.modelInput);
        configSummaryCard = findViewById(R.id.configSummaryCard);
        configGuideContainer = findViewById(R.id.configGuideContainer);
        configuredModelText = findViewById(R.id.configuredModelText);
        configuredEndpointText = findViewById(R.id.configuredEndpointText);
        connectionStatusText = findViewById(R.id.connectionStatusText);
        apiKeyHelpText = findViewById(R.id.apiKeyHelpText);
        testConnectionButton = findViewById(R.id.testConnectionButton);
        saveConfigButton = findViewById(R.id.saveConfigButton);
        clearApiKeyButton = findViewById(R.id.clearApiKeyButton);
        clearAiCacheButton = findViewById(R.id.clearAiCacheButton);
        settingsProgress = findViewById(R.id.settingsProgress);
    }

    private void bindActions() {
        findViewById(R.id.settingsBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.modifyConfigButton).setOnClickListener(view -> showGuide());
        testConnectionButton.setOnClickListener(view -> testConnection());
        saveConfigButton.setOnClickListener(view -> saveConfiguration());
        clearApiKeyButton.setOnClickListener(view -> confirmClearApiKey());
        clearAiCacheButton.setOnClickListener(view -> confirmClearCache());
    }

    private void renderState(boolean keepGuideOpen) {
        ApiConfig config = configStore.getConfig();
        boolean configured = config.isComplete();
        configSummaryCard.setVisibility(configured && !keepGuideOpen ? View.VISIBLE : View.GONE);
        configGuideContainer.setVisibility(!configured || keepGuideOpen ? View.VISIBLE : View.GONE);

        if (configured) {
            configuredModelText.setText(config.getModel());
            configuredEndpointText.setText(AiEndpoint.normalizeBaseUrl(config.getBaseUrl()));
            connectionStatusText.setText(configStore.wasConnectionSuccessful(config)
                    ? R.string.connection_ok
                    : R.string.connection_configured);
        }
        if (configGuideContainer.getVisibility() == View.VISIBLE) {
            baseUrlInput.setText(config.getBaseUrl());
            modelInput.setText(config.getModel());
            apiKeyInput.setText("");
            apiKeyHelpText.setText(configStore.hasStoredApiKey()
                    ? R.string.api_key_saved_help
                    : R.string.api_key_secure_help);
        }
        clearApiKeyButton.setEnabled(configStore.hasStoredApiKey());
        clearAiCacheButton.setText(getString(
                R.string.clear_ai_cache_count,
                explanationCache.count()
        ));
    }

    private void showGuide() {
        renderState(true);
    }

    private void testConnection() {
        if (requestRunning) {
            return;
        }
        ApiConfig draft = readDraftConfig();
        if (draft == null) {
            return;
        }
        setRequestRunning(true);
        executor.execute(() -> {
            try {
                client.testConnection(draft);
                lastSuccessfulDraftFingerprint = ApiConfigStore.fingerprint(draft);
                ApiConfig saved = configStore.getConfig();
                if (saved.isComplete()
                        && ApiConfigStore.fingerprint(saved).equals(lastSuccessfulDraftFingerprint)) {
                    configStore.markConnectionSuccessful(saved);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setRequestRunning(false);
                    Toast.makeText(this, R.string.connection_test_success, Toast.LENGTH_SHORT).show();
                    if (configSummaryCard.getVisibility() == View.VISIBLE) {
                        renderState(false);
                    }
                });
            } catch (OpenAiCompatibleClient.AiClientException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setRequestRunning(false);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.connection_failed)
                            .setMessage(e.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    private void saveConfiguration() {
        ApiConfig draft = readDraftConfig();
        if (draft == null) {
            return;
        }
        String enteredKey = textOf(apiKeyInput);
        try {
            configStore.save(
                    draft.getBaseUrl(),
                    enteredKey.isEmpty() ? null : enteredKey,
                    draft.getModel()
            );
            ApiConfig saved = configStore.getConfig();
            if (!saved.isComplete()) {
                apiKeyInputLayout.setError(getString(R.string.enter_valid_api_key));
                return;
            }
            if (ApiConfigStore.fingerprint(saved).equals(lastSuccessfulDraftFingerprint)) {
                configStore.markConnectionSuccessful(saved);
            }
            Toast.makeText(this, R.string.configuration_saved, Toast.LENGTH_SHORT).show();
            renderState(false);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.save_failed)
                    .setMessage(e.getMessage() == null
                            ? getString(R.string.unable_to_save_configuration)
                            : e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private ApiConfig readDraftConfig() {
        clearErrors();
        String baseUrl = textOf(baseUrlInput);
        String model = textOf(modelInput);
        String enteredKey = textOf(apiKeyInput);
        String apiKey = enteredKey.isEmpty() ? configStore.getConfig().getApiKey() : enteredKey;
        boolean valid = true;
        try {
            AiEndpoint.validateHttpsBaseUrl(baseUrl);
        } catch (IllegalArgumentException e) {
            baseUrlInputLayout.setError(e.getMessage());
            valid = false;
        }
        if (apiKey.isEmpty()) {
            apiKeyInputLayout.setError(getString(R.string.enter_api_key));
            valid = false;
        }
        if (model.isEmpty()) {
            modelInputLayout.setError(getString(R.string.enter_model_name));
            valid = false;
        }
        return valid ? new ApiConfig(baseUrl, apiKey, model) : null;
    }

    private void clearErrors() {
        baseUrlInputLayout.setError(null);
        apiKeyInputLayout.setError(null);
        modelInputLayout.setError(null);
    }

    private void setRequestRunning(boolean running) {
        requestRunning = running;
        settingsProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        testConnectionButton.setEnabled(!running);
        saveConfigButton.setEnabled(!running);
        testConnectionButton.setText(running
                ? R.string.testing_connection
                : R.string.test_connection);
    }

    private void confirmClearApiKey() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_api_key)
                .setMessage(R.string.clear_api_key_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    configStore.clearApiKey();
                    lastSuccessfulDraftFingerprint = "";
                    Toast.makeText(this, R.string.api_key_cleared, Toast.LENGTH_SHORT).show();
                    renderState(true);
                })
                .show();
    }

    private void confirmClearCache() {
        if (explanationCache.count() == 0) {
            Toast.makeText(this, R.string.no_ai_cache, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_ai_cache)
                .setMessage(R.string.clear_ai_cache_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    explanationCache.clear();
                    clearAiCacheButton.setText(getString(R.string.clear_ai_cache_count, 0));
                    Toast.makeText(this, R.string.ai_cache_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static String textOf(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
