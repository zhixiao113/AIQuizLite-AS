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

    private String editingProfile = "default";
    private android.widget.TextView profileSummary;
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

        if (savedInstanceState != null) editingProfile = savedInstanceState.getString("editing_profile", "default");
        if (!new AiProfiles(this).ids().contains(editingProfile)) editingProfile = "default";
        configStore = new ApiConfigStore(this, editingProfile);
        explanationCache = new AiExplanationCache(this);
        bindViews();
        bindActions();
        bindProfiles();
        renderState(savedInstanceState != null && savedInstanceState.getBoolean("guide_open", false));
        if (savedInstanceState != null && savedInstanceState.getBoolean("guide_open", false)) {
            baseUrlInput.setText(savedInstanceState.getString("draft_url", ""));
            modelInput.setText(savedInstanceState.getString("draft_model", ""));
            Object retained = getLastCustomNonConfigurationInstance();
            if (retained instanceof String[]) {
                String[] draft = (String[]) retained;
                if (draft.length == 2 && editingProfile.equals(draft[0])) apiKeyInput.setText(draft[1]);
            }
        }
    }

    private void bindProfiles() {
        profileSummary = findViewById(R.id.profileSummary);
        findViewById(R.id.selectAiProfileButton).setOnClickListener(v -> guardDraft(this::chooseProfile));
        findViewById(R.id.addAiProfileButton).setOnClickListener(v -> guardDraft(() -> editName(false)));
        findViewById(R.id.importAiProfilesButton).setOnClickListener(v -> guardDraft(this::importProfiles));
        findViewById(R.id.manageAiProfileButton).setOnClickListener(v -> guardDraft(this::manageProfile));
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { requestExit(); }
        });
        updateProfiles();
    }

    private void chooseProfile() {
        AiProfiles profiles = new AiProfiles(this);
        java.util.List<String> ids = profiles.ids();
        String[] names = new String[ids.size()];
        for (int n = 0; n < ids.size(); n++) names[n] = profileLabel(profiles, ids.get(n));
        new AlertDialog.Builder(this).setTitle("选择 AI 配置")
                .setSingleChoiceItems(names, ids.indexOf(editingProfile), (dialog, which) -> {
                    dialog.dismiss(); selectProfile(ids.get(which));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private String profileLabel(AiProfiles profiles, String id) {
        String roles = (id.equals(profiles.role("text")) ? " · 文字" : "")
                + (id.equals(profiles.role("vision")) ? " · 图片" : "");
        boolean configured = new ApiConfigStore(this, id).getConfig().isComplete();
        return profiles.name(id) + "（" + (profiles.enabled(id) ? "启用" : "停用")
                + (configured ? "" : " · 未配置") + roles + "）";
    }

    private void editName(boolean rename) {
        AiProfiles profiles = new AiProfiles(this);
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("配置名称，例如：图片模型");
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        if (rename) name.setText(profiles.name(editingProfile));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(rename ? "重命名 AI" : "添加 AI")
                .setView(name).setNegativeButton(android.R.string.cancel, null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("请填写名称"); return; }
            if (rename) { profiles.rename(editingProfile, value); updateProfiles(); }
            else {
                if (profiles.ids().size() >= 50) { name.setError("最多保留 50 个配置"); return; }
                selectProfile(profiles.add(value));
            }
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void manageProfile() {
        AiProfiles profiles = new AiProfiles(this);
        String[] actions = {profiles.enabled(editingProfile) ? "停用此 AI" : "启用此 AI",
                "用于纯文字讲解", "用于图片理解", "重命名", "删除此配置"};
        new AlertDialog.Builder(this).setTitle(profiles.name(editingProfile)).setItems(actions, (dialog, which) -> {
            if (which == 0) { profiles.enable(editingProfile, !profiles.enabled(editingProfile)); updateProfiles(); }
            else if (which == 1 || which == 2) {
                try { profiles.assign(which == 1 ? "text" : "vision", editingProfile); updateProfiles(); }
                catch (IllegalArgumentException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
            } else if (which == 3) editName(true);
            else if ("default".equals(editingProfile)) {
                Toast.makeText(this, "原有 AI 请使用停用或清除 Key", Toast.LENGTH_SHORT).show();
            } else {
                new AlertDialog.Builder(this).setTitle("删除 AI 配置？")
                    .setMessage("此配置的 Key 和分工会一并移除，已有讲解缓存会保留。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("删除", (d, w) -> { profiles.delete(editingProfile); selectProfile("default"); }).show();
            }
        }).show();
    }

    private void importProfiles() {
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, 0, pad, 0);
        TextView help = new TextView(this);
        help.setText("粘贴 JSON 数组，每次 1–20 个配置。name、baseUrl、apiKey、model 必填；enabled 默认 true；text、vision 指定分工，每种分工最多一个。导入后会新增配置；只有指定的分工会替换现有选择。\n示例：[{\"name\":\"文字 AI\",\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"填写 Key\",\"model\":\"填写模型名\",\"text\":true}]");
        content.addView(help);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("粘贴配置 JSON（含 Key，请勿分享）");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(5); input.setMaxLines(9); input.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(AiProfileImport.MAX_CHARACTERS + 1)});
        content.addView(input);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("批量导入 AI")
                .setView(scroll).setNegativeButton(android.R.string.cancel, null).setPositiveButton("导入", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                java.util.List<AiProfileImport.Entry> entries = AiProfileImport.parse(input.getText().toString());
                String first = new AiProfiles(this).importEntries(entries);
                input.setText(""); dialog.dismiss(); selectProfile(first);
                Toast.makeText(this, "已导入 " + entries.size() + " 个 AI 配置", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException | IllegalStateException e) { input.setError(e.getMessage()); }
        }));
        dialog.setOnDismissListener(ignored -> input.setText(""));
        dialog.show();
    }

    private void requestExit() {
        if (requestRunning) { finish(); return; }
        guardDraft(() -> finish());
    }

    private void guardDraft(Runnable action) {
        if (requestRunning) return;
        if (!hasUnsavedDraft()) { action.run(); return; }
        new AlertDialog.Builder(this).setTitle("当前配置尚未保存")
                .setMessage("先保存当前 AI，或放弃本次修改。")
                .setPositiveButton("保存后继续", (d, w) -> { if (saveConfiguration()) action.run(); })
                .setNeutralButton("放弃修改", (d, w) -> { renderState(false); action.run(); })
                .setNegativeButton("继续编辑", null).show();
    }

    private boolean hasUnsavedDraft() {
        if (configGuideContainer.getVisibility() != View.VISIBLE) return false;
        ApiConfig saved = configStore.getConfig();
        return !AiEndpoint.normalizeBaseUrl(textOf(baseUrlInput)).equals(AiEndpoint.normalizeBaseUrl(saved.getBaseUrl()))
                || !textOf(modelInput).equals(saved.getModel()) || !textOf(apiKeyInput).isEmpty();
    }

    private void selectProfile(String id) {
        editingProfile = id;
        configStore = new ApiConfigStore(this, id);
        lastSuccessfulDraftFingerprint = "";
        clearErrors(); renderState(true); updateProfiles();
    }

    private void updateProfiles() {
        AiProfiles profiles = new AiProfiles(this);
        profileSummary.setText("当前编辑：" + profileLabel(profiles, editingProfile)
                + "\n文字讲解：" + roleLabel(profiles, "text") + "\n图片理解：" + roleLabel(profiles, "vision"));
    }

    private String roleLabel(AiProfiles profiles, String role) {
        String id = profiles.role(role);
        return id.isEmpty() ? "未指定" : profileLabel(profiles, id);
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
        findViewById(R.id.settingsBackButton).setOnClickListener(view -> requestExit());
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

    private boolean saveConfiguration() {
        ApiConfig draft = readDraftConfig();
        if (draft == null) {
            return false;
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
                return false;
            }
            if (ApiConfigStore.fingerprint(saved).equals(lastSuccessfulDraftFingerprint)) {
                configStore.markConnectionSuccessful(saved);
            }
            Toast.makeText(this, R.string.configuration_saved, Toast.LENGTH_SHORT).show();
            renderState(false);
            updateProfiles();
            return true;
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.save_failed)
                    .setMessage(e.getMessage() == null
                            ? getString(R.string.unable_to_save_configuration)
                            : e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return false;
        }
    }

    private ApiConfig readDraftConfig() {
        clearErrors();
        String baseUrl = textOf(baseUrlInput);
        String model = textOf(modelInput);
        String enteredKey = textOf(apiKeyInput);
        ApiConfig saved = configStore.getConfig();
        boolean sameEndpoint = AiEndpoint.normalizeBaseUrl(baseUrl).equals(AiEndpoint.normalizeBaseUrl(saved.getBaseUrl()));
        String apiKey = enteredKey.isEmpty() && sameEndpoint ? saved.getApiKey() : enteredKey;
        boolean valid = true;
        try {
            AiEndpoint.validateHttpsBaseUrl(baseUrl);
            if (java.net.URI.create(baseUrl).getRawUserInfo() != null)
                throw new IllegalArgumentException("API 地址不能包含用户名或密码");
        } catch (IllegalArgumentException e) {
            baseUrlInputLayout.setError(e.getMessage());
            valid = false;
        }
        if (apiKey.isEmpty()) {
            apiKeyInputLayout.setError(!sameEndpoint && configStore.hasStoredApiKey()
                    ? "更换 API 地址时请重新填写 Key，避免发送到其他服务" : getString(R.string.enter_api_key));
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
        baseUrlInput.setEnabled(!running); apiKeyInput.setEnabled(!running); modelInput.setEnabled(!running);
        clearApiKeyButton.setEnabled(!running && configStore.hasStoredApiKey());
        for (int id : new int[]{R.id.selectAiProfileButton, R.id.addAiProfileButton, R.id.importAiProfilesButton, R.id.manageAiProfileButton})
            findViewById(id).setEnabled(!running);
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
                    updateProfiles();
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
    protected void onSaveInstanceState(Bundle state) {
        state.putString("editing_profile", editingProfile);
        state.putBoolean("guide_open", configGuideContainer.getVisibility() == View.VISIBLE);
        state.putString("draft_url", textOf(baseUrlInput));
        state.putString("draft_model", textOf(modelInput));
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainCustomNonConfigurationInstance() {
        // Keep an unsaved Key only in memory across rotation, never in an instance-state Bundle.
        return new String[]{editingProfile, textOf(apiKeyInput)};
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
