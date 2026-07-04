package com.example.aiquizlite;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;

public class DailyHistoryActivity extends AppCompatActivity {
    private TextView summaryText;
    private TextView emptyText;
    private LinearLayout recordsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daily_history);

        summaryText = findViewById(R.id.historySummaryText);
        emptyText = findViewById(R.id.historyEmptyText);
        recordsContainer = findViewById(R.id.historyRecordsContainer);

        String questionBankId = getIntent().getStringExtra(QuizActivity.EXTRA_QUESTION_BANK);
        QuestionRepository.QuestionBank questionBank =
                QuestionRepository.findQuestionBank(this, questionBankId);
        List<ProgressStore.DailyPracticeRecord> records =
                new ProgressStore(this).getDailyPracticeRecords(questionBank.getId());

        summaryText.setText(questionBank.getDisplayName() + " · 共 " + records.size() + " 次");
        bindRecords(records);
    }

    private void bindRecords(List<ProgressStore.DailyPracticeRecord> records) {
        recordsContainer.removeAllViews();

        if (records.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }

        emptyText.setVisibility(View.GONE);
        for (int i = records.size() - 1; i >= 0; i--) {
            ProgressStore.DailyPracticeRecord record = records.get(i);
            recordsContainer.addView(createHistoryRow(i + 1, record));
        }
    }

    private View createHistoryRow(int sequenceNumber, ProgressStore.DailyPracticeRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.card_background);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        TextView title = new TextView(this);
        title.setText(String.format(
                Locale.getDefault(),
                "第 %d 次 · %s",
                sequenceNumber,
                android.text.format.DateFormat.format("MM-dd HH:mm", record.getTimestamp())
        ));
        title.setTextColor(getColor(R.color.brand_ink));
        title.setTextSize(15);

        TextView detail = new TextView(this);
        detail.setText(
                "答对 " + record.getCorrectCount()
                        + " 题，答错 " + record.getWrongCount()
                        + " 题，正确率 " + record.getAccuracyPercent() + "%"
        );
        detail.setTextColor(getColor(R.color.brand_text_secondary));
        detail.setTextSize(14);
        detail.setPadding(0, dp(6), 0, 0);

        row.addView(title);
        row.addView(detail);
        return row;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
