package com.example.aiquizlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
        ApiConfig config = new AiProfiles(activity).config("text");
        AiExplanationCache cache = new AiExplanationCache(activity);
        String cacheKey = AiExplanationCache.keyFor(
                questionBankId,
                question,
                selectedAnswers,
                correctAnswers,
                selectedPairs,
                correctPairs,
                correct,
                config
        );
        AiExplanation found = cache.get(cacheKey);
        if (found == null && AiHybridExplanation.images(question).isEmpty()) {
            found = cache.get(AiExplanationCache.legacyKeyFor(questionBankId, question, selectedAnswers,
                    correctAnswers, selectedPairs, correctPairs, correct, config));
            if (found != null) cache.put(cacheKey, found);
        }
        final AiExplanation cached = found;
        if (cached == null && !config.isComplete()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.ai_configuration_required)
                    .setMessage(R.string.ai_configuration_required_detail)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.go_to_settings, (dialog, which) ->
                            activity.startActivity(new Intent(activity, SettingsActivity.class)))
                    .show();
            return;
        }

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
                config,
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
            ApiConfig config,
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
                AiExplanation explanation = AiExplanationRequests.SHARED.get(cacheKey, cache, force,
                        () -> AiHybridExplanation.generate(appContext, questionBankId, question,
                                config, systemPrompt, userPrompt));
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    content.setTag(Boolean.FALSE);
                    subtitle.setText(R.string.ai_explanation);
                    render(content, question, explanation);
                    loading.setVisibility(View.GONE);
                    error.setVisibility(View.GONE);
                    result.setVisibility(View.VISIBLE);
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
        if (question.isFillBlank()) {
            return String.join(" / ", answers);
        }
        return String.join("", answers);
    }
}
