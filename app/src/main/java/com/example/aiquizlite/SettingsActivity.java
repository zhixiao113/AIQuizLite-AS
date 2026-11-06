package com.example.aiquizlite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SettingsActivity extends AppCompatActivity {
    static final String EXTRA_MODEL_ID = "ai_model_id";
    private static final String STATE_EDITING = "ai_settings_editing";
    private static final String STATE_MODEL = "ai_settings_model";
    private static final long REFRESH_MILLIS = 30_000;

    private final ExecutorService tests = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshCooling;
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    private AiProfiles profiles;
    private AiExplanationCache cache;
    private FrameLayout content;
    private TextView title;
    private MaterialButton add;
    private AiProfiles.Model original;
    private boolean editing;
    private boolean replacingKey;
    private boolean updatingFields;
    private String lastUrl;
    private TextInputLayout urlLayout, modelLayout, keyLayout;
    private TextInputEditText url, model, key;
    private View maskedKey;
    private TextView keyHint, notice, cooling, modelHeading;
    private ImageView icon;
    private MaterialButton replaceKey, test, save;
    private AiModelProbe.Result proof;
    private Future<?> pendingTest;
    private boolean testing;
    private int testGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        setContentView(R.layout.activity_settings);
        profiles = new AiProfiles(this);
        cache = new AiExplanationCache(this);
        content = findViewById(R.id.aiSettingsContent);
        title = findViewById(R.id.aiSettingsTitle);
        add = findViewById(R.id.aiSettingsAdd);
        add.setOnClickListener(view -> openModel(null));
        findViewById(R.id.aiSettingsBack).setOnClickListener(view -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        if (state != null && state.getBoolean(STATE_EDITING)) {
            openModel(state.getString(STATE_MODEL));
        } else if (state == null && getIntent().hasExtra(EXTRA_MODEL_ID)) {
            openModel(getIntent().getStringExtra(EXTRA_MODEL_ID));
        } else {
            showList();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        refreshCooling();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    private void refreshCooling() {
        long now = System.currentTimeMillis();
        long next = REFRESH_MILLIS;
        if (editing) {
            AiProfiles.Model saved = original == null ? null : profiles.model(original.id);
            updateDetailHeader(saved, now);
            if (saved != null && saved.isCooling(now)) next = Math.min(next, saved.cooldownUntil - now);
        } else {
            List<AiProfiles.Model> entries = profiles.models();
            renderList(entries, now);
            for (AiProfiles.Model entry : entries) {
                if (entry.isCooling(now)) next = Math.min(next, entry.cooldownUntil - now);
            }
        }
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, Math.max(next, 100L));
    }

    private void showList() {
        cancelTest();
        editing = false;
        original = null;
        proof = null;
        hideKeyboard();
        renderList(profiles.models(), System.currentTimeMillis());
    }

    private void renderList(List<AiProfiles.Model> entries, long now) {
        title.setText(R.string.ai_models_title);
        add.setVisibility(View.VISIBLE);
        ScrollView previous = content.findViewById(R.id.aiModelsScroll);
        int scrollY = previous == null ? 0 : previous.getScrollY();
        content.removeAllViews();
        ScrollView list = (ScrollView) getLayoutInflater().inflate(R.layout.ai_model_settings_list, content, false);
        content.addView(list);
        LinearLayout rows = list.findViewById(R.id.aiModelsRows);
        int available = 0;
        for (AiProfiles.Model entry : entries) {
            if (entry.enabled && entry.config.isComplete() && !entry.isCooling(now)) available++;
            if (rows.getChildCount() != 0) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.brand_divider));
                rows.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
            bindModelRow(rows, entry, now);
        }
        list.findViewById(R.id.aiModelsListCard).setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        list.findViewById(R.id.aiModelsEmpty).setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        list.findViewById(R.id.aiModelsListHint).setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        TextView count = list.findViewById(R.id.aiModelsAvailableCount);
        count.setText(getString(R.string.ai_models_available, available));
        count.setBackground(background(R.color.brand_primary_soft, 8));
        MaterialButton clear = list.findViewById(R.id.aiModelsClearCache);
        clear.setText(getString(R.string.clear_ai_cache_count, cache.count()));
        clear.setOnClickListener(view -> confirmClearCache());
        if (scrollY > 0) list.post(() -> list.scrollTo(0, scrollY));
    }

    private void bindModelRow(LinearLayout rows, AiProfiles.Model entry, long now) {
        View row = getLayoutInflater().inflate(R.layout.ai_model_settings_row, rows, false);
        boolean cooled = entry.isCooling(now);
        String name = displayName(entry);
        if (cooled) row.setBackgroundColor(getColor(R.color.brand_border_light));
        View edit = row.findViewById(R.id.aiModelEditRow);
        edit.setAlpha(entry.enabled && !cooled ? 1f : .76f);
        edit.setOnClickListener(view -> openModel(entry.id));
        edit.setOnLongClickListener(view -> { confirmDelete(entry); return true; });
        ViewCompat.addAccessibilityAction(edit, getString(R.string.ai_models_row_delete, name),
                (view, arguments) -> { confirmDelete(entry); return true; });
        TextView rowName = row.findViewById(R.id.aiModelRowName);
        rowName.setText(name);
        TextView state = row.findViewById(R.id.aiModelRowState);
        if (cooled) state.setText(coolingLabel(entry.cooldownUntil - now));
        else if (!entry.config.isComplete()) state.setText(R.string.ai_models_incomplete);
        state.setVisibility(cooled || !entry.config.isComplete() ? View.VISIBLE : View.GONE);
        edit.setContentDescription(getString(R.string.ai_models_row_edit, name)
                + (state.getVisibility() == View.VISIBLE ? "，" + state.getText() : ""));
        ImageView rowIcon = row.findViewById(R.id.aiModelRowIcon);
        styleIcon(rowIcon, entry.config.getModel(), cooled || !entry.enabled);
        SwitchMaterial toggle = row.findViewById(R.id.aiModelRowToggle);
        toggle.setContentDescription(getString(R.string.ai_models_row_toggle, name));
        toggle.setChecked(entry.enabled && !cooled);
        toggle.setEnabled(!cooled);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            profiles.enable(entry.id, checked);
            refreshCooling();
        });
        rows.addView(row);
    }

    private void openModel(String id) {
        cancelTest();
        original = id == null ? null : profiles.model(id);
        if (id != null && original == null) { showList(); return; }
        editing = true;
        replacingKey = false;
        proof = null;
        title.setText(original == null ? R.string.ai_models_add_title : R.string.ai_models_edit_title);
        add.setVisibility(View.GONE);
        content.removeAllViews();
        getLayoutInflater().inflate(R.layout.ai_model_settings_detail, content, true);
        urlLayout = content.findViewById(R.id.aiDetailUrlLayout);
        modelLayout = content.findViewById(R.id.aiDetailIdLayout);
        keyLayout = content.findViewById(R.id.aiDetailKeyLayout);
        url = content.findViewById(R.id.aiDetailUrl);
        model = content.findViewById(R.id.aiDetailId);
        key = content.findViewById(R.id.aiDetailKey);
        maskedKey = content.findViewById(R.id.aiDetailMaskedKey);
        keyHint = content.findViewById(R.id.aiDetailKeyHint);
        notice = content.findViewById(R.id.aiDetailNotice);
        cooling = content.findViewById(R.id.aiDetailCooling);
        modelHeading = content.findViewById(R.id.aiDetailModel);
        icon = content.findViewById(R.id.aiDetailIcon);
        replaceKey = content.findViewById(R.id.aiDetailReplaceKey);
        test = content.findViewById(R.id.aiDetailTest);
        save = content.findViewById(R.id.aiDetailSave);
        test.setOnClickListener(view -> testConnection());
        save.setOnClickListener(view -> save());
        replaceKey.setOnClickListener(view -> {
            replacingKey = true;
            updateKeyState();
            key.requestFocus();
            key.post(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(key, InputMethodManager.SHOW_IMPLICIT));
        });
        Draft retained = getLastCustomNonConfigurationInstance() instanceof Draft
                ? (Draft) getLastCustomNonConfigurationInstance() : null;
        if (retained != null && !retained.used && java.util.Objects.equals(id, retained.id)) {
            retained.used = true;
            url.setText(retained.url);
            model.setText(retained.model);
            key.setText(retained.key);
            replacingKey = retained.replacing;
            proof = retained.proof;
        } else if (original != null) {
            url.setText(original.config.getBaseUrl());
            model.setText(original.config.getModel());
        }
        lastUrl = AiEndpoint.normalizeBaseUrl(text(url));
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) { onDraftChanged(); }
        };
        url.addTextChangedListener(watcher);
        model.addTextChangedListener(watcher);
        key.addTextChangedListener(watcher);
        updateKeyState();
        updateDetailHeader(original, System.currentTimeMillis());
        updateButtons();
        setNotice(getString(proof == null ? R.string.ai_models_test_first : R.string.ai_models_test_passed), false);
    }

    private void updateDetailHeader(AiProfiles.Model saved, long now) {
        boolean cooled = saved != null && saved.isCooling(now);
        modelHeading.setText(saved == null ? getString(R.string.ai_models_new) : displayName(saved));
        styleIcon(icon, saved == null ? "" : saved.config.getModel(), saved != null && (cooled || !saved.enabled));
        cooling.setVisibility(cooled ? View.VISIBLE : View.GONE);
        if (cooled) {
            cooling.setBackground(background(R.color.ai_model_cooldown_surface, 11));
            cooling.setText(getString(R.string.ai_models_cooling_hint, coolingLabel(saved.cooldownUntil - now)));
        }
    }

    private String displayName(AiProfiles.Model entry) {
        String name = entry.config.getModel();
        return name.isEmpty() ? getString(R.string.ai_models_incomplete) : name;
    }

    private void onDraftChanged() {
        if (updatingFields) return;
        String normalized = AiEndpoint.normalizeBaseUrl(text(url));
        if (!lastUrl.equals(normalized) && key.length() != 0) {
            updatingFields = true;
            key.setText("");
            updatingFields = false;
        }
        lastUrl = normalized;
        proof = null;
        urlLayout.setError(null);
        modelLayout.setError(null);
        keyLayout.setError(null);
        updateKeyState();
        setNotice(getString(R.string.ai_models_test_first), false);
        updateButtons();
    }

    private boolean sameAddress() {
        return original != null && AiEndpoint.normalizeBaseUrl(text(url))
                .equals(AiEndpoint.normalizeBaseUrl(original.config.getBaseUrl()));
    }

    private void updateKeyState() {
        boolean retained = sameAddress() && !original.config.getApiKey().isEmpty();
        maskedKey.setVisibility(retained && !replacingKey ? View.VISIBLE : View.GONE);
        keyLayout.setVisibility(retained && !replacingKey ? View.GONE : View.VISIBLE);
        keyHint.setText(retained ? R.string.ai_models_retain_key : original != null && !sameAddress()
                ? R.string.ai_models_changed_key : R.string.ai_models_enter_key);
    }

    private ApiConfig draft() {
        String entered = text(key);
        String effectiveKey = entered.isEmpty() && sameAddress() ? original.config.getApiKey() : entered;
        return new ApiConfig(AiEndpoint.normalizeBaseUrl(text(url)), effectiveKey, text(model));
    }

    private ApiConfig validatedDraft() {
        ApiConfig config = draft();
        try {
            AiEndpoint.validateHttpsBaseUrl(config.getBaseUrl());
        } catch (IllegalArgumentException error) {
            urlLayout.setError(error.getMessage());
            url.requestFocus();
            return null;
        }
        if (config.getModel().isEmpty()) {
            modelLayout.setError(getString(R.string.ai_models_enter_id));
            model.requestFocus();
            return null;
        }
        if (config.getApiKey().isEmpty()) {
            keyLayout.setError(getString(original != null && !sameAddress()
                    ? R.string.ai_models_changed_key : R.string.ai_models_enter_key));
            key.requestFocus();
            return null;
        }
        return config;
    }

    private void testConnection() {
        if (testing) return;
        ApiConfig config = validatedDraft();
        if (config == null) return;
        proof = null;
        testing = true;
        int generation = ++testGeneration;
        hideKeyboard();
        setNotice(getString(R.string.testing_connection), false);
        updateButtons();
        pendingTest = tests.submit(() -> {
            try {
                AiModelProbe.Result result = AiModelProbe.test(config, client);
                runOnUiThread(() -> onTestComplete(generation, result, null));
            } catch (OpenAiCompatibleClient.AiClientException error) {
                runOnUiThread(() -> onTestComplete(generation, null, error.getMessage()));
            }
        });
    }

    private void onTestComplete(int generation, AiModelProbe.Result result, String error) {
        if (generation != testGeneration || !editing || isFinishing() || isDestroyed()) return;
        pendingTest = null;
        testing = false;
        proof = result;
        if (result == null) {
            setNotice(error, true);
        } else {
            boolean recovered = original != null && profiles.recoverAfterTest(original.id, result);
            AiProfiles.Model saved = original == null ? null : profiles.model(original.id);
            updateDetailHeader(saved, System.currentTimeMillis());
            setNotice(getString(recovered ? R.string.ai_models_test_recovered
                    : saved != null && saved.isCooling(System.currentTimeMillis())
                    ? R.string.ai_models_test_edit_recovery : R.string.ai_models_test_passed), false);
        }
        updateButtons();
    }

    private void updateButtons() {
        url.setEnabled(!testing);
        model.setEnabled(!testing);
        key.setEnabled(!testing);
        replaceKey.setEnabled(!testing);
        test.setEnabled(!testing);
        test.setText(testing ? R.string.testing_connection : R.string.test_connection);
        save.setEnabled(!testing && proof != null && proof.matches(draft()));
    }

    private void save() {
        ApiConfig config = validatedDraft();
        if (config == null || proof == null || !proof.matches(config)) return;
        try {
            boolean adding = original == null;
            profiles.save(adding ? null : original.id, config, proof);
            key.setText("");
            showList();
            Toast.makeText(this, adding ? R.string.ai_models_added : R.string.ai_models_saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException | IllegalStateException error) {
            setNotice(error.getMessage(), true);
        }
    }

    private void setNotice(String message, boolean error) {
        notice.setBackground(background(error ? R.color.brand_error_soft : R.color.brand_primary_soft, 9));
        notice.setTextColor(getColor(error ? R.color.brand_error_dark : R.color.brand_text_secondary));
        notice.setText(message);
    }

    private void confirmDelete(AiProfiles.Model entry) {
        new AlertDialog.Builder(this).setTitle(R.string.ai_models_delete_title)
                .setIcon(AiModelIcons.match(entry.config.getModel()).drawable)
                .setMessage(getString(R.string.ai_models_delete_body, displayName(entry)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ai_models_delete, (dialog, which) -> {
                    profiles.delete(entry.id);
                    refreshCooling();
                    Toast.makeText(this, R.string.ai_models_deleted, Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void confirmClearCache() {
        if (cache.count() == 0) {
            Toast.makeText(this, R.string.no_ai_cache, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.clear_ai_cache)
                .setMessage(R.string.clear_ai_cache_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    cache.clear();
                    refreshCooling();
                    Toast.makeText(this, R.string.ai_cache_cleared, Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void back() {
        if (!editing) { finish(); return; }
        if (dirty()) {
            new AlertDialog.Builder(this).setTitle(R.string.ai_models_discard_title)
                    .setMessage(R.string.ai_models_discard_body)
                    .setNegativeButton(R.string.ai_models_continue_editing, null)
                    .setPositiveButton(R.string.ai_models_discard, (dialog, which) -> showList()).show();
        } else showList();
    }

    private boolean dirty() {
        if (original == null) return !text(url).isEmpty() || !text(model).isEmpty() || !text(key).isEmpty();
        return !sameAddress() || !text(model).equals(original.config.getModel()) || !text(key).isEmpty();
    }

    private void cancelTest() {
        testGeneration++;
        if (pendingTest != null) pendingTest.cancel(true);
        pendingTest = null;
        testing = false;
    }

    private String coolingLabel(long millis) {
        long minutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        long hours = minutes / 60;
        String duration = hours >= 24 ? getString(R.string.ai_models_time_days, hours / 24, hours % 24)
                : hours > 0 ? getString(R.string.ai_models_time_hours, hours, minutes % 60)
                : getString(R.string.ai_models_time_minutes, minutes);
        return getString(R.string.ai_models_cooling, duration);
    }

    private void styleIcon(ImageView image, String modelId, boolean muted) {
        GradientDrawable circle = background(muted ? R.color.brand_border_light : R.color.brand_primary_soft, 50);
        image.setBackground(circle);
        image.setPadding(dp(7), dp(7), dp(7), dp(7));
        AiModelIcons.bind(image, modelId, muted);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(ColorStateList.valueOf(getColor(color)));
        return shape;
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String text(TextInputEditText field) { return field.getText() == null ? "" : field.getText().toString().trim(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_EDITING, editing);
        if (editing && original != null) state.putString(STATE_MODEL, original.id);
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainCustomNonConfigurationInstance() {
        return editing ? new Draft(original == null ? null : original.id, text(url), text(model), text(key), replacingKey, proof) : null;
    }

    @Override protected void onDestroy() {
        cancelTest();
        handler.removeCallbacks(refresh);
        tests.shutdownNow();
        super.onDestroy();
    }

    private static final class Draft {
        final String id, url, model, key;
        final boolean replacing;
        final AiModelProbe.Result proof;
        boolean used;

        Draft(String id, String url, String model, String key, boolean replacing, AiModelProbe.Result proof) {
            this.id = id;
            this.url = url;
            this.model = model;
            this.key = key;
            this.replacing = replacing;
            this.proof = proof;
        }
    }
}
