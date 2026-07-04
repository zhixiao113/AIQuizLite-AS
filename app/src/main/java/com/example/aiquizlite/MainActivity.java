package com.example.aiquizlite;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    public static final String PREF_NAME = "main_activity";
    public static final String KEY_SELECTED_BANK = "selected_bank";
    private static final int HISTORY_PREVIEW_LIMIT = 3;

    private String selectedQuestionBankId;
    private TextView summaryText;
    private TextView wrongCountText;
    private MaterialAutoCompleteTextView questionBankEntry;
    private MaterialButton reverseOrderButton;
    private MaterialButton refreshHistoryButton;
    private MaterialButton moreHistoryButton;
    private DailyStatsChartView historyChartView;
    private LinearLayout historyRecordsContainer;
    private TextView historyEmptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        questionBankEntry = findViewById(R.id.questionBankDropdown);
        summaryText = findViewById(R.id.summaryText);
        wrongCountText = findViewById(R.id.wrongCountText);
        refreshHistoryButton = findViewById(R.id.refreshHistoryButton);
        moreHistoryButton = findViewById(R.id.moreHistoryButton);
        historyChartView = findViewById(R.id.historyChartView);
        historyRecordsContainer = findViewById(R.id.historyRecordsContainer);
        historyEmptyText = findViewById(R.id.historyEmptyText);
        MaterialButton dailyButton = findViewById(R.id.dailyButton);
        MaterialButton wrongButton = findViewById(R.id.wrongButton);
        MaterialButton mockButton = findViewById(R.id.mockButton);
        TextView restartDailyButton = findViewById(R.id.restartDailyButton);
        reverseOrderButton = findViewById(R.id.reverseOrderButton);

        selectedQuestionBankId = restoreSelectedQuestionBankId();
        setupQuestionBankEntry();
        refreshHomeContent();

        dailyButton.setOnClickListener(view -> openMode(QuizActivity.MODE_DAILY));
        wrongButton.setOnClickListener(view -> openMode(QuizActivity.MODE_WRONG));
        mockButton.setOnClickListener(view -> openMode(QuizActivity.MODE_MOCK));
        restartDailyButton.setOnClickListener(view -> confirmRestartDailyPractice());
        reverseOrderButton.setOnClickListener(view -> toggleDailyOrder());
        refreshHistoryButton.setOnClickListener(view -> clearDailyHistory());
        moreHistoryButton.setOnClickListener(view -> openAllHistory());
    }

    @Override
    protected void onResume() {
        super.onResume();
        QuestionRepository.resetCache();
        selectedQuestionBankId = restoreSelectedQuestionBankId();
        setupQuestionBankEntry();
        refreshHomeContent();
    }

    private void setupQuestionBankEntry() {
        QuestionRepository.QuestionBank questionBank =
                QuestionRepository.findQuestionBank(this, selectedQuestionBankId);
        selectedQuestionBankId = questionBank.getId();
        persistSelectedQuestionBankId();
        questionBankEntry.setText(questionBank.getDisplayName(), false);
        questionBankEntry.setOnClickListener(view -> openQuestionBankPage());
        questionBankEntry.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                openQuestionBankPage();
                view.clearFocus();
            }
        });
    }

    private void openQuestionBankPage() {
        Intent intent = new Intent(this, QuestionBankActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUESTION_BANK, selectedQuestionBankId);
        startActivity(intent);
    }

    private void refreshHomeContent() {
        ProgressStore store = new ProgressStore(this);
        QuestionRepository.QuestionBank questionBank =
                QuestionRepository.findQuestionBank(this, selectedQuestionBankId);
        selectedQuestionBankId = questionBank.getId();
        List<Question> questions = QuestionRepository.load(this, questionBank.getId());

        summaryText.setText(
                questionBank.getDisplayName()
                        + " 题库共 "
                        + questions.size()
                        + " 题，覆盖单选、多选、判断。"
        );
        wrongCountText.setText("错题本当前 " + store.getWrongCount(questionBank.getId()) + " 题");
        reverseOrderButton.setText(store.isDailyOrderReversed(questionBank.getId()) ? "已倒序" : "倒序");
        reverseOrderButton.setAlpha(store.hasDailySessionInProgress(questionBank.getId()) ? 0.6f : 1f);
        bindDailyHistory(store, questionBank.getId());
    }

    private void openMode(String mode) {
        Intent intent = new Intent(this, QuizActivity.class);
        intent.putExtra(QuizActivity.EXTRA_MODE, mode);
        intent.putExtra(QuizActivity.EXTRA_QUESTION_BANK, selectedQuestionBankId);
        startActivity(intent);
    }

    private void confirmRestartDailyPractice() {
        new AlertDialog.Builder(this)
                .setTitle("重新开始")
                .setMessage("确认从头开始当前题库的日常刷题吗？错题本不会被清空。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("重新开始", (dialog, which) -> {
                    new ProgressStore(this).clearDailySession(selectedQuestionBankId);
                    Toast.makeText(this, "已从头开始", Toast.LENGTH_SHORT).show();
                    refreshHomeContent();
                })
                .show();
    }

    private void toggleDailyOrder() {
        ProgressStore store = new ProgressStore(this);
        if (store.hasDailySessionInProgress(selectedQuestionBankId)) {
            Toast.makeText(this, "请先完成本轮答题，或点击重新开始", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean reversed = store.toggleDailyOrderReversed(selectedQuestionBankId);
        reverseOrderButton.setText(reversed ? "已倒序" : "倒序");
        reverseOrderButton.setAlpha(1f);
        Toast.makeText(this, reversed ? "下一轮将按倒序刷题" : "下一轮将按正序刷题", Toast.LENGTH_SHORT).show();
    }

    private String restoreSelectedQuestionBankId() {
        String storedQuestionBankId = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString(KEY_SELECTED_BANK, null);
        return QuestionRepository.findQuestionBank(this, storedQuestionBankId).getId();
    }

    private void persistSelectedQuestionBankId() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_BANK, selectedQuestionBankId)
                .apply();
    }

    private void bindDailyHistory(ProgressStore store, String questionBankId) {
        List<ProgressStore.DailyPracticeRecord> records = store.getDailyPracticeRecords(questionBankId);
        ProgressStore.DailySession dailySession = store.getDailySession(questionBankId);
        historyChartView.setRecords(records);
        historyRecordsContainer.removeAllViews();
        moreHistoryButton.setVisibility(View.GONE);

        if (dailySession != null) {
            historyRecordsContainer.addView(createInProgressRow(dailySession));
        }

        if (records.isEmpty() && dailySession == null) {
            historyEmptyText.setVisibility(View.VISIBLE);
            return;
        }

        historyEmptyText.setVisibility(View.GONE);
        int startIndex = Math.max(0, records.size() - HISTORY_PREVIEW_LIMIT);
        List<ProgressStore.DailyPracticeRecord> previewRecords =
                new ArrayList<>(records.subList(startIndex, records.size()));

        for (int i = previewRecords.size() - 1; i >= 0; i--) {
            ProgressStore.DailyPracticeRecord record = previewRecords.get(i);
            historyRecordsContainer.addView(createHistoryRow(startIndex + i + 1, record));
        }

        if (records.size() > HISTORY_PREVIEW_LIMIT) {
            moreHistoryButton.setVisibility(View.VISIBLE);
        }
    }

    private void clearDailyHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.refresh)
                .setMessage(R.string.clear_daily_history_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.refresh, (dialog, which) -> {
                    ProgressStore store = new ProgressStore(this);
                    store.clearDailyPracticeRecords(selectedQuestionBankId);
                    bindDailyHistory(store, selectedQuestionBankId);
                    Toast.makeText(this, R.string.daily_history_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void openAllHistory() {
        Intent intent = new Intent(this, DailyHistoryActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUESTION_BANK, selectedQuestionBankId);
        startActivity(intent);
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

    private View createInProgressRow(ProgressStore.DailySession session) {
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
        title.setText("本轮进行中");
        title.setTextColor(getColor(R.color.brand_primary));
        title.setTextSize(15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView detail = new TextView(this);
        detail.setText(
                "已答 " + session.getAnsweredCount()
                        + " 题，答对 " + session.getCorrectCount()
                        + " 题，当前第 " + (session.getCurrentIndex() + 1) + " 题"
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
