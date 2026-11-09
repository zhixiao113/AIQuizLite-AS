package com.example.aiquizlite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Per-window selection is separate from the model that produced the visible content. */
final class AiExplanationDialog {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final Context appContext;
    private final String bank;
    private final Question question;
    private final List<String> answers;
    private final List<String> correctAnswers;
    private final List<MatchingData.Pair> selectedPairs;
    private final List<MatchingData.Pair> correctPairs;
    private final boolean correct;
    private final boolean needsVision;
    private final ExecutorService executor;
    private final AiProfiles profiles;
    private final AiExplanationCache cache;
    private final GeneralSettingsStore settings;
    private final String questionKey;
    private final SharedPreferences configPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener configListener;
    private AlertDialog dialog;
    private View content;
    private TextView subtitle;
    private View modelButton;
    private ImageView modelIcon;
    private MaterialButton regenerate;
    private AiExplanationModelMenu menu;
    private AiTermPopup terms;
    private AiExplanation lastGood;
    private AiProfiles.Model contentSource;
    private String selectedProfileId;
    private boolean lastForce;
    private boolean loading;
    private int requestNumber;
    private Future<?> waiter;

    AiExplanationDialog(Activity activity, String bank, Question question, List<String> answers,
            List<String> correctAnswers, List<MatchingData.Pair> selectedPairs,
            List<MatchingData.Pair> correctPairs, boolean correct, ExecutorService executor) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.bank = bank;
        this.question = question;
        this.answers = answers == null ? new ArrayList<>() : new ArrayList<>(answers);
        this.correctAnswers = correctAnswers == null ? new ArrayList<>() : new ArrayList<>(correctAnswers);
        this.selectedPairs = selectedPairs == null ? null : new ArrayList<>(selectedPairs);
        this.correctPairs = correctPairs == null ? null : new ArrayList<>(correctPairs);
        this.correct = correct;
        this.executor = executor;
        profiles = new AiProfiles(appContext);
        cache = new AiExplanationCache(appContext);
        settings = new GeneralSettingsStore(appContext);
        needsVision = !AiModelExplanation.images(question).isEmpty();
        questionKey = AiExplanationCache.keyFor(bank, question, this.answers, this.correctAnswers,
                this.selectedPairs, this.correctPairs, correct, null);
        configPreferences = appContext.getSharedPreferences(ApiConfigStore.PREF_NAME, Context.MODE_PRIVATE);
        configListener = (preferences, key) -> {
            if (menu != null && dialog.isShowing()) menu.refresh();
        };
    }

    void show() {
        List<AiProfiles.Model> available = availableModels();
        if (available.isEmpty()) {
            showUnavailable();
            return;
        }
        AiProfiles.Model first = available.get(0);
        selectedProfileId = first.id;
        content = activity.getLayoutInflater().inflate(R.layout.dialog_ai_explanation, null);
        dialog = new AlertDialog.Builder(activity).setView(content).create();
        subtitle = content.findViewById(R.id.aiDialogSubtitle);
        modelButton = content.findViewById(R.id.aiDialogModelButton);
        modelIcon = content.findViewById(R.id.aiDialogModelIcon);
        modelIcon.setClipToOutline(true);
        regenerate = content.findViewById(R.id.aiRegenerateButton);
        ScrollView scroll = content.findViewById(R.id.aiExplanationScroll);
        terms = new AiTermPopup(activity, dialog, scroll);
        ((AiExplanationCard) content).setOnContentTap(terms::dismiss);
        menu = new AiExplanationModelMenu(activity, dialog, modelButton, profiles, needsVision,
                () -> selectedProfileId, () -> lastGood == null ? "" : lastGood.getProfileId(), this::selectModel);
        modelButton.setOnClickListener(view -> {
            if (!loading) menu.toggle();
        });
        regenerate.setOnClickListener(view -> request(true));
        content.findViewById(R.id.aiRetryButton).setOnClickListener(view -> request(lastForce));
        content.findViewById(R.id.aiCheckSettingsButton).setOnClickListener(view -> {
            dialog.dismiss();
            activity.startActivity(new Intent(activity, SettingsActivity.class));
        });
        content.findViewById(R.id.aiDialogCloseButton).setOnClickListener(view -> dialog.dismiss());
        content.findViewById(R.id.aiDismissButton).setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            requestNumber++;
            menu.dismiss();
            terms.close();
            ((AiExplanationCard) content).setOnContentTap(null);
            configPreferences.unregisterOnSharedPreferenceChangeListener(configListener);
            // The shared generation still finishes and saves its result for the next opening.
            if (waiter != null) waiter.cancel(true);
        });
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                android.graphics.Rect visible = new android.graphics.Rect();
                activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
                int width = visible.width() > 0 ? visible.width() : activity.getResources().getDisplayMetrics().widthPixels;
                int height = visible.height() > 0 ? visible.height() : activity.getResources().getDisplayMetrics().heightPixels;
                window.setLayout(Math.round(width * 0.92f), Math.round(height * 0.86f));
            }
            modelIcon.post(() -> {
                TextView title = content.findViewById(R.id.aiDialogTitle);
                ViewGroup.LayoutParams size = modelIcon.getLayoutParams();
                size.width = title.getLineHeight();
                size.height = title.getLineHeight();
                modelIcon.setLayoutParams(size);
                ViewGroup.LayoutParams touch = modelButton.getLayoutParams();
                int minTouch = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                touch.width = Math.max(minTouch, title.getLineHeight() + modelIcon.getPaddingLeft() * 2);
                touch.height = touch.width;
                modelButton.setLayoutParams(touch);
            });
            configPreferences.registerOnSharedPreferenceChangeListener(configListener);
            AiExplanation found = findCached(first);
            if (found != null) accept(AiExplanationRouter.Result.cached(found));
            else request(false);
        });
        dialog.show();
    }

    private AiExplanation findCached(AiProfiles.Model first) {
        AiExplanation found = cache.get(AiExplanationCache.modelKeyFor(questionKey, first.id));
        if (found != null && (found.getModelId().isEmpty()
                || first.config.getModel().equals(found.getModelId()))) return found;
        found = cache.get(questionKey);
        if (found != null && (found.getProfileId().isEmpty()
                || first.id.equals(found.getProfileId()) && first.config.getModel().equals(found.getModelId()))) return found;
        if (!needsVision) {
            found = cache.get(AiExplanationCache.legacyKeyFor(bank, question, answers, correctAnswers,
                    selectedPairs, correctPairs, correct, first.config));
            if (found != null) {
                cache.put(AiExplanationCache.modelKeyFor(questionKey, first.id), found);
                return found;
            }
        }
        return null;
    }

    private List<AiProfiles.Model> availableModels() {
        return AiExplanationRouter.eligibleModels(profiles, needsVision, System.currentTimeMillis());
    }

    private void selectModel(AiProfiles.Model selected) {
        if (loading) return;
        AiProfiles.Model current = profiles.model(selected.id);
        if (!AiExplanationRouter.eligible(current, needsVision, System.currentTimeMillis())) {
            showUnavailable();
            return;
        }
        selectedProfileId = current.id;
        if (settings.isAiModelSwitchRegenerateEnabled()) {
            String key = AiExplanationCache.modelKeyFor(questionKey, current.id);
            AiExplanation cached = AiExplanationRequests.cachedForModel(cache, key, current);
            if (cached != null) accept(AiExplanationRouter.Result.cached(cached));
            else request(true);
        } else {
            bindModel(current.config.getModel());
            subtitle.setText(activity.getString(R.string.ai_dialog_waiting_model, current.config.getModel()));
        }
    }

    private void request(boolean force) {
        if (loading || !dialog.isShowing()) return;
        AiProfiles.Model selected = profiles.model(selectedProfileId);
        boolean pinned = force && AiExplanationRouter.eligible(selected, needsVision, System.currentTimeMillis());
        if (!AiExplanationRouter.eligible(selected, needsVision, System.currentTimeMillis())) {
            List<AiProfiles.Model> models = availableModels();
            if (models.isEmpty()) {
                showUnavailable();
                return;
            }
            selected = models.get(0);
        }
        selectedProfileId = selected.id;
        lastForce = force;
        loading = true;
        int number = ++requestNumber;
        menu.dismiss();
        terms.dismiss();
        modelButton.setEnabled(false);
        regenerate.setEnabled(false);
        content.findViewById(R.id.aiRetryButton).setEnabled(false);
        bindModel(selected.config.getModel());
        subtitle.setText(activity.getString(R.string.ai_dialog_preparing_model, selected.config.getModel()));
        showState(R.id.aiLoadingContainer);

        String system = AiExplanationPromptBuilder.systemPrompt(force);
        String prompt = AiExplanationPromptBuilder.userPrompt(question, answers, correct,
                formatAnswers(answers), formatAnswers(correctAnswers), selectedPairs, correctPairs);
        Context context = appContext;
        String bankId = bank;
        Question requestQuestion = question;
        String cacheKey = questionKey;
        AiExplanationCache requestCache = cache;
        AiProfiles.Model target = selected;
        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        WeakReference<AiExplanationDialog> weak = new WeakReference<>(this);
        Consumer<AiProfiles.Model> progress = model -> post(weak, number, session -> session.showAttempt(model));
        try {
            waiter = executor.submit(() -> {
                try {
                    AiExplanationRouter.Result generated = AiExplanationRequests.SHARED.getForModel(
                            cacheKey, target, requestCache, force, pinned,
                            () -> AiModelExplanation.generate(context, bankId, requestQuestion, system,
                                    prompt, target.id, pinned, client, progress));
                    post(weak, number, session -> session.accept(generated));
                } catch (OpenAiCompatibleClient.AiClientException exception) {
                    post(weak, number, session -> session.failed(exception.getMessage(), null));
                }
            });
        } catch (RejectedExecutionException exception) {
            failed(activity.getString(R.string.ai_dialog_failed), null);
        }
    }

    private static void post(WeakReference<AiExplanationDialog> weak, int number,
            Consumer<AiExplanationDialog> action) {
        MAIN.post(() -> {
            AiExplanationDialog session = weak.get();
            if (session != null && session.requestNumber == number && session.dialog.isShowing()
                    && !session.activity.isFinishing() && !session.activity.isDestroyed()) action.accept(session);
        });
    }

    private void showAttempt(AiProfiles.Model model) {
        bindModel(model.config.getModel());
        subtitle.setText(activity.getString(R.string.ai_dialog_generating_model, model.config.getModel()));
    }

    private void accept(AiExplanationRouter.Result generated) {
        if (generated.explanation == null) {
            failed(generated.error, generated.failures);
            return;
        }
        lastGood = generated.explanation;
        contentSource = generated.model;
        if (contentSource == null && !lastGood.getProfileId().isEmpty()) {
            AiProfiles.Model saved = profiles.model(lastGood.getProfileId());
            if (saved != null && saved.config.getModel().equals(lastGood.getModelId())) contentSource = saved;
        }
        if (!lastGood.getProfileId().isEmpty()) selectedProfileId = lastGood.getProfileId();
        finishLoading();
        render();
        showSource(generated.cached);
        if (!generated.failures.isEmpty()) AiExplanationDialogLauncher.showFailures(activity, dialog, generated.failures);
    }

    private void failed(String message, List<AiExplanationRouter.Failure> failures) {
        finishLoading();
        if (lastGood != null) {
            selectedProfileId = lastGood.getProfileId().isEmpty() ? null : lastGood.getProfileId();
            render();
            showSource(false);
        } else {
            subtitle.setText(R.string.ai_dialog_failed);
            ((TextView) content.findViewById(R.id.aiErrorText)).setText(message);
            showState(R.id.aiErrorContainer);
            bindModel("");
        }
        if (failures != null && !failures.isEmpty()) {
            AiExplanationDialogLauncher.showFailures(activity, dialog, failures);
        } else if (lastGood != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private void finishLoading() {
        loading = false;
        modelButton.setEnabled(true);
        regenerate.setEnabled(lastGood != null);
        content.findViewById(R.id.aiRetryButton).setEnabled(true);
    }

    private void showSource(boolean cached) {
        bindModel(lastGood.getModelId());
        if (lastGood.getModelId().isEmpty()) subtitle.setText(R.string.ai_dialog_legacy_cache);
        else subtitle.setText(activity.getString(cached ? R.string.ai_dialog_from_cache : R.string.ai_dialog_from_model,
                lastGood.getModelId()));
    }

    private void bindModel(String modelId) {
        AiModelIcons.bind(modelIcon, modelId, false);
        modelButton.setContentDescription(modelId.isEmpty() ? activity.getString(R.string.ai_dialog_choose_model)
                : activity.getString(R.string.ai_dialog_model_accessibility, modelId));
    }

    private void render() {
        terms.dismiss();
        String context = question.getStem() + "\n" + lastGood.toDisplayText();
        bind(R.id.aiConclusionSection, R.id.aiConclusionText, lastGood.getConclusion(), context);
        bind(R.id.aiReasoningSection, R.id.aiReasoningText, lastGood.getReasoning(), context);
        bind(R.id.aiMistakeSection, R.id.aiMistakeText, lastGood.getCommonMistake(), context);
        bind(R.id.aiMemorySection, R.id.aiMemoryText, lastGood.getMemoryTip(), context);
        View originalCard = content.findViewById(R.id.originalExplanationCard);
        originalCard.setVisibility(question.getExplanation() == null ? View.GONE : View.VISIBLE);
        ((TextView) content.findViewById(R.id.originalExplanationText)).setText(question.getExplanation());
        showState(R.id.aiContentContainer);
    }

    private void bind(int sectionId, int textId, String value, String context) {
        content.findViewById(sectionId).setVisibility(value == null || value.trim().isEmpty() ? View.GONE : View.VISIBLE);
        terms.bind(content.findViewById(textId), value, contentSource, context);
    }

    private void showState(int visible) {
        for (int id : new int[]{R.id.aiLoadingContainer, R.id.aiErrorContainer, R.id.aiContentContainer}) {
            content.findViewById(id).setVisibility(id == visible ? View.VISIBLE : View.GONE);
        }
    }

    private void showUnavailable() {
        int message = needsVision ? R.string.ai_dialog_vision_unavailable : R.string.ai_dialog_models_unavailable;
        if (dialog != null && dialog.isShowing()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        else new AlertDialog.Builder(activity).setMessage(message).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.go_to_settings, (ignored, which) ->
                        activity.startActivity(new Intent(activity, SettingsActivity.class))).show();
    }

    private String formatAnswers(List<String> values) {
        return values.isEmpty() ? "未作答" : TextUtils.join(question.isFillBlank() ? " / " : "", values);
    }
}
