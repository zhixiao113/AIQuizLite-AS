package com.example.aiquizlite;

import android.app.Activity;
import android.content.Intent;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;

public final class AiExplanationDialogLauncher {
    private AiExplanationDialogLauncher() {}

    public static void show(Activity activity, String questionBankId, Question question,
            List<String> selectedAnswers, List<String> correctAnswers, boolean correct,
            ExecutorService executor) {
        show(activity, questionBankId, question, selectedAnswers, correctAnswers,
                null, null, correct, executor);
    }

    public static void show(Activity activity, String questionBankId, Question question,
            List<String> selectedAnswers, List<String> correctAnswers,
            List<MatchingData.Pair> selectedPairs, List<MatchingData.Pair> correctPairs,
            boolean correct, ExecutorService executor) {
        new AiExplanationDialog(activity, questionBankId, question, selectedAnswers, correctAnswers,
                selectedPairs, correctPairs, correct, executor).show();
    }

    static void showFailures(Activity activity, AlertDialog explanation,
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

}
