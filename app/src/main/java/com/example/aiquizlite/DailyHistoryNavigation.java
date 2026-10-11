package com.example.aiquizlite;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Both the home preview and the complete history list use the same review entry. */
final class DailyHistoryNavigation {
    private DailyHistoryNavigation() { }

    static void bind(Context context, LinearLayout row, String bankId, String detailId, boolean inProgress) {
        if (!new GeneralSettingsStore(context).shouldReviewDailyHistory()) return;
        boolean canReview = inProgress || (detailId != null && !detailId.isEmpty());
        TextView action = new TextView(context);
        action.setText(canReview ? R.string.daily_review_open : R.string.daily_review_no_detail);
        action.setTextSize(13);
        action.setTextColor(context.getColor(canReview ? R.color.brand_primary : R.color.brand_text_secondary));
        action.setPadding(0, Math.round(context.getResources().getDisplayMetrics().density * 8), 0, 0);
        row.addView(action);
        if (canReview) {
            row.setFocusable(true);
            row.setOnClickListener(view -> context.startActivity(new Intent(context, QuizActivity.class)
                    .putExtra(QuizActivity.EXTRA_MODE, QuizActivity.MODE_DAILY_REVIEW)
                    .putExtra(QuizActivity.EXTRA_QUESTION_BANK, bankId)
                    .putExtra(QuizActivity.EXTRA_REVIEW_ID, detailId)
                    .putExtra(QuizActivity.EXTRA_REVIEW_IN_PROGRESS, inProgress)));
        }
    }
}
