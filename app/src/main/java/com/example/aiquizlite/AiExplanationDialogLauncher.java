package com.example.aiquizlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.concurrent.ExecutorService;

public final class AiExplanationDialogLauncher {
    private AiExplanationDialogLauncher() {
    }

    public static void show(
            Activity activity,
            String questionBankId,
            Question question,
            List<String> selectedAnswers,
            List<String> correctAnswers,
            boolean correct,
            ExecutorService executor
    ) {
        show(activity, questionBankId, question, selectedAnswers, correctAnswers,
                null, null, correct, executor);
    }

    public static void show(
            Activity activity,
            String questionBankId,
            Question question,
            List<String> selectedAnswers,
            List<String> correctAnswers,
            List<MatchingData.Pair> selectedPairs,
            List<MatchingData.Pair> correctPairs,
            boolean correct,
            ExecutorService executor
    ) {
        AiExplanationCache cache = new AiExplanationCache(activity);
        String cacheKey = AiExplanationCache.keyFor(
                questionBankId,
                question,
                selectedAnswers,
                correctAnswers,
                selectedPairs,
                correctPairs,
                correct,
                null
        );
        AiExplanation found = cache.get(cacheKey);
        if (found == null && AiModelExplanation.images(question).isEmpty()) {
            for (AiProfiles.Model model : new AiProfiles(activity).models()) {
                found = cache.get(AiExplanationCache.legacyKeyFor(questionBankId, question, selectedAnswers,
                        correctAnswers, selectedPairs, correctPairs, correct, model.config));
                if (found != null) {
                    cache.put(cacheKey, found);
                    break;
                }
            }
        }
        final AiExplanation cached = found;

        View content = activity.getLayoutInflater().inflate(R.layout.dialog_ai_explanation, null);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).create();
        TextView subtitle = content.findViewById(R.id.aiDialogSubtitle);
        View loading = content.findViewById(R.id.aiLoadingContainer);
        View error = content.findViewById(R.id.aiErrorContainer);
        View result = content.findViewById(R.id.aiContentContainer);
        TextView errorText = content.findViewById(R.id.aiErrorText);
        MaterialButton retry = content.findViewById(R.id.aiRetryButton);
        MaterialButton settings = content.findViewById(R.id.aiCheckSettingsButton);
        MaterialButton close = content.findViewById(R.id.aiDismissButton);
        MaterialButton regenerate = content.findViewById(R.id.aiRegenerateButton);

        content.findViewById(R.id.aiDialogCloseButton).setOnClickListener(view -> dialog.dismiss());
        settings.setOnClickListener(view -> {
            dialog.dismiss();
            activity.startActivity(new Intent(activity, SettingsActivity.class));
        });
        close.setOnClickListener(view -> dialog.dismiss());

        boolean[] lastForce = {false};
        java.util.function.Consumer<Boolean> request = force -> {
            lastForce[0] = force;
            request(
                activity,
                questionBankId,
                dialog,
                content,
                subtitle,
                loading,
                error,
                result,
                errorText,
                question,
                selectedAnswers,
                correctAnswers,
                selectedPairs,
                correctPairs,
                correct,
                cacheKey,
                cache,
                force,
                executor
        );
        };
        retry.setOnClickListener(view -> request.accept(lastForce[0]));
        regenerate.setOnClickListener(view -> request.accept(true));
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(
                        (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
            }
            if (cached == null) {
                request.accept(false);
            } else {
                subtitle.setText(R.string.ai_dialog_cached);
                render(content, question, cached);
                loading.setVisibility(View.GONE);
                error.setVisibility(View.GONE);
                result.setVisibility(View.VISIBLE);
            }
        });
        dialog.show();
    }

    private static void request(
            Activity activity,
            String questionBankId,
            AlertDialog dialog,
            View content,
            TextView subtitle,
            View loading,
            View error,
            View result,
            TextView errorText,
            Question question,
            List<String> selectedAnswers,
            List<String> correctAnswers,
            List<MatchingData.Pair> selectedPairs,
            List<MatchingData.Pair> correctPairs,
            boolean correct,
            String cacheKey,
            AiExplanationCache cache,
            boolean force,
            ExecutorService executor
    ) {
        if (Boolean.TRUE.equals(content.getTag())) return;
        content.setTag(Boolean.TRUE);
        subtitle.setText(R.string.ai_dialog_generating);
        loading.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        result.setVisibility(View.GONE);
        String systemPrompt = AiExplanationPromptBuilder.systemPrompt();
        String userPrompt = AiExplanationPromptBuilder.userPrompt(
                question,
                selectedAnswers,
                correct,
                formatAnswers(question, selectedAnswers),
                formatAnswers(question, correctAnswers),
                selectedPairs,
                correctPairs
        );
        executor.execute(() -> {
            try {
                android.content.Context appContext = activity.getApplicationContext();
                AiExplanationRouter.Result generated = AiExplanationRequests.SHARED.get(cacheKey, cache, force,
                        () -> AiModelExplanation.generate(appContext, questionBankId, question, systemPrompt, userPrompt));
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    content.setTag(Boolean.FALSE);
                    loading.setVisibility(View.GONE);
                    if (generated.explanation == null) {
                        subtitle.setText(R.string.ai_dialog_failed);
                        errorText.setText(generated.error);
                        error.setVisibility(View.VISIBLE);
                    } else {
                        subtitle.setText(generated.cached ? R.string.ai_dialog_cached : R.string.ai_explanation);
                        render(content, question, generated.explanation);
                        result.setVisibility(View.VISIBLE);
                    }
                    if (!generated.failures.isEmpty()) showFailures(activity, dialog, generated.failures);
                });
            } catch (OpenAiCompatibleClient.AiClientException exception) {
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    content.setTag(Boolean.FALSE);
                    subtitle.setText(R.string.ai_dialog_failed);
                    errorText.setText(exception.getMessage());
                    loading.setVisibility(View.GONE);
                    result.setVisibility(View.GONE);
                    error.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private static void showFailures(Activity activity, AlertDialog explanation,
            List<AiExplanationRouter.Failure> failures) {
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(activity, 22), dp(activity, 6), dp(activity, 22), dp(activity, 6));
        for (AiExplanationRouter.Failure failure : failures) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            AiModelIcons.bind(icon, failure.modelId, failure.cooled);
            row.addView(icon, new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28)));
            TextView label = new TextView(activity);
            label.setPadding(dp(activity, 10), 0, 0, 0);
            String message = failure.cooled ? activity.getString(R.string.ai_model_failed_cooldown, failure.reason) : failure.reason;
            label.setText(failure.modelId + "\n" + message);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.ai_model_call_failed_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                    Intent intent = new Intent(activity, SettingsActivity.class);
                    if (failures.size() == 1) intent.putExtra(SettingsActivity.EXTRA_MODEL_ID, failures.get(0).profileId);
                    explanation.dismiss();
                    activity.startActivity(intent);
                })
                .show();
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void render(View content, Question question, AiExplanation explanation) {
        bind(content, R.id.aiConclusionSection, R.id.aiConclusionText, explanation.getConclusion());
        bind(content, R.id.aiReasoningSection, R.id.aiReasoningText, explanation.getReasoning());
        bind(content, R.id.aiMistakeSection, R.id.aiMistakeText, explanation.getCommonMistake());
        bind(content, R.id.aiMemorySection, R.id.aiMemoryText, explanation.getMemoryTip());
        View originalCard = content.findViewById(R.id.originalExplanationCard);
        TextView originalText = content.findViewById(R.id.originalExplanationText);
        if (question.getExplanation() == null) {
            originalCard.setVisibility(View.GONE);
        } else {
            originalText.setText(question.getExplanation());
            originalCard.setVisibility(View.VISIBLE);
        }
    }

    private static void bind(View content, int sectionId, int textId, String value) {
        View section = content.findViewById(sectionId);
        TextView text = content.findViewById(textId);
        if (value == null || value.trim().isEmpty()) {
            section.setVisibility(View.GONE);
        } else {
            text.setText(value.trim());
            section.setVisibility(View.VISIBLE);
        }
    }

    private static String formatAnswers(Question question, List<String> answers) {
        if (answers == null || answers.isEmpty()) {
            return "未作答";
        }
        return TextUtils.join(question.isFillBlank() ? " / " : "", answers);
    }
}
