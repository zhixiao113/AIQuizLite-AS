package com.example.aiquizlite;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.button.MaterialButton;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MockResultActivity extends AppCompatActivity {
    private static final String FILTER_ALL = "all";
    private static final String FILTER_WRONG = "wrong";
    private static final String FILTER_UNANSWERED = "unanswered";
    private static final String FILTER_FAVORITE = "favorite";

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Question> questionsById = new HashMap<>();
    private final List<MockExamAttempt.Item> filteredItems = new ArrayList<>();
    private MockExamStore mockExamStore;
    private ProgressStore progressStore;
    private GeneralSettingsStore generalSettings;
    private MockExamAttempt attempt;
    private QuestionRepository.QuestionBank questionBank;
    private LinearLayout content;
    private boolean reviewMode;
    private String activeFilter = FILTER_ALL;
    private int reviewIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        String attemptId = getIntent().getStringExtra(QuizActivity.EXTRA_MOCK_ATTEMPT_ID);
        mockExamStore = new MockExamStore(this);
        progressStore = new ProgressStore(this);
        generalSettings = new GeneralSettingsStore(this);
        attempt = mockExamStore.load(attemptId);
        if (attempt == null || !attempt.isCompleted()) {
            Toast.makeText(this, "未找到已完成的模拟考试记录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        questionBank = QuestionRepository.findQuestionBank(this, attempt.getQuestionBankId());
        for (Question question : QuestionRepository.load(this, attempt.getQuestionBankId())) {
            questionsById.put(question.getId(), question);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getColor(R.color.brand_surface));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(36));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scrollView);
        showSummary();
    }

    private void showSummary() {
        reviewMode = false;
        content.removeAllViews();
        addHeader("模拟考试结果", questionBank.getDisplayName(), view -> finish());

        CardView scoreCard = card();
        LinearLayout scoreBody = vertical(dp(22));
        TextView title = text("本次得分", 15, R.color.brand_text_secondary, false);
        scoreBody.addView(title);
        LinearLayout scoreRow = horizontal();
        scoreRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView score = text(String.valueOf(attempt.getScore()), 42, R.color.brand_primary, true);
        score.setGravity(Gravity.CENTER);
        GradientDrawable scoreBackground = rounded(Color.WHITE, dp(70));
        scoreBackground.setStroke(dp(7), getColor(R.color.brand_primary_soft));
        score.setBackground(scoreBackground);
        scoreRow.addView(score, new LinearLayout.LayoutParams(dp(118), dp(118)));
        LinearLayout stats = vertical(dp(18));
        stats.addView(statLine("答对", attempt.getCorrectCount(), R.color.brand_success));
        stats.addView(statLine("答错", attempt.getWrongCount(), R.color.brand_error));
        stats.addView(statLine("未作答", attempt.getUnansweredCount(), R.color.brand_warning));
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        statsParams.leftMargin = dp(28);
        scoreRow.addView(stats, statsParams);
        LinearLayout.LayoutParams scoreRowParams = wrapParams();
        scoreRowParams.topMargin = dp(16);
        scoreBody.addView(scoreRow, scoreRowParams);
        scoreCard.addView(scoreBody);
        content.addView(scoreCard, cardParams());

        MaterialButton review = primaryButton("查看答题详情");
        review.setOnClickListener(view -> {
            activeFilter = FILTER_ALL;
            reviewIndex = 0;
            showReview();
        });
        content.addView(review, buttonParams(dp(18)));

        MaterialButton retry = outlineButton("只练本次错题");
        retry.setEnabled(attempt.getWrongCount() > 0);
        retry.setOnClickListener(view -> startWrongRetry());
        content.addView(retry, buttonParams(dp(10)));

        addMissedTypeSummary();

        CardView noticeCard = card();
        noticeCard.setCardBackgroundColor(getColor(R.color.brand_primary_soft));
        LinearLayout noticeBody = vertical(dp(18));
        noticeBody.addView(text("本次考试规则", 17, R.color.brand_ink, true));
        TextView notice = text(
                "题目顺序已随机；普通选择题的选项内容已随机，但显示标签始终为 A、B、C、D。"
                        + "判断题以及引用选项字母、先后顺序的题目不会乱序。",
                14,
                R.color.brand_text_secondary,
                false
        );
        notice.setLineSpacing(dp(4), 1f);
        noticeBody.addView(notice, buttonParams(dp(8)));
        noticeCard.addView(noticeBody);
        content.addView(noticeCard, cardParams());
    }

    private void addMissedTypeSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MockExamAttempt.Item item : attempt.getItems()) {
            if (item.isCorrect()) {
                continue;
            }
            Question question = questionsById.get(item.getQuestionId());
            if (question != null) {
                String label = question.typeLabel();
                counts.put(label, counts.getOrDefault(label, 0) + 1);
            }
        }
        CardView card = card();
        LinearLayout body = vertical(dp(20));
        body.addView(text("薄弱题型", 20, R.color.brand_ink, true));
        if (counts.isEmpty()) {
            body.addView(text("本次全部答对，继续保持。", 15, R.color.brand_text_secondary, false), buttonParams(dp(10)));
        } else {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                TextView row = text(
                        entry.getKey() + "  ·  " + entry.getValue() + " 题需复习",
                        15,
                        R.color.brand_text_secondary,
                        false
                );
                body.addView(row, buttonParams(dp(12)));
            }
        }
        card.addView(body);
        content.addView(card, cardParams());
    }

    private void showReview() {
        reviewMode = true;
        rebuildFilter();
        content.removeAllViews();
        addHeader("答题详情", "考试得分保持为 " + attempt.getScore() + " 分", view -> showSummary());
        addFilters();
        if (filteredItems.isEmpty()) {
            CardView empty = card();
            TextView emptyText = text("当前筛选下没有题目", 16, R.color.brand_text_secondary, false);
            emptyText.setGravity(Gravity.CENTER);
            empty.setContentPadding(dp(20), dp(48), dp(20), dp(48));
            empty.addView(emptyText);
            content.addView(empty, cardParams());
            return;
        }
        reviewIndex = Math.max(0, Math.min(reviewIndex, filteredItems.size() - 1));
        MockExamAttempt.Item item = filteredItems.get(reviewIndex);
        Question question = questionsById.get(item.getQuestionId());
        if (question == null) {
            filteredItems.remove(reviewIndex);
            showReview();
            return;
        }
        SessionQuestion session = new SessionQuestion(question, item.getOptionKeyOrder(), true);

        CardView metaCard = card();
        LinearLayout metaBody = vertical(dp(18));
        TextView progress = text(
                "第 " + (reviewIndex + 1) + " / " + filteredItems.size() + " 题",
                21,
                R.color.brand_ink,
                true
        );
        metaBody.addView(progress);
        String status = item.getSelectedAnswers().isEmpty()
                ? "未作答"
                : item.isCorrect() ? "回答正确" : "回答错误";
        int statusColor = item.getSelectedAnswers().isEmpty()
                ? R.color.brand_warning
                : item.isCorrect() ? R.color.brand_success : R.color.brand_error;
        TextView statusText = text(status, 14, statusColor, true);
        metaBody.addView(statusText, buttonParams(dp(6)));
        metaCard.addView(metaBody);
        content.addView(metaCard, cardParams());

        CardView questionCard = card();
        LinearLayout questionBody = vertical(dp(isCompactDensity() ? 16 : 20));
        TextView source = text(
                question.getPaper() + " · 原卷第 " + question.getNumberInPaper() + " 题 · " + question.typeLabel(),
                13,
                R.color.brand_text_muted,
                false
        );
        questionBody.addView(source);
        TextView stem = text(
                question.getIndex() + ". " + question.getStem(),
                isLargeQuestionFont() ? 22 : 19,
                R.color.brand_ink,
                true
        );
        stem.setLineSpacing(dp(5), 1f);
        questionBody.addView(stem, buttonParams(dp(isCompactDensity() ? 9 : 12)));
        addQuestionImage(questionBody, question);
        addOptionRows(questionBody, session, item);

        TextView answerSummary = text(
                "你的答案：" + formatMappedAnswers(session, question, item.getSelectedAnswers())
                        + "\n正确答案：" + formatMappedAnswers(session, question, item.getCorrectAnswers()),
                isLargeQuestionFont() ? 17 : 15,
                R.color.brand_text_secondary,
                false
        );
        answerSummary.setLineSpacing(dp(5), 1f);
        questionBody.addView(answerSummary, buttonParams(dp(12)));

        if (generalSettings.shouldShowSourceExplanation()) {
            TextView explanationTitle = text(
                    "题库解析",
                    isLargeQuestionFont() ? 18 : 16,
                    R.color.brand_ink,
                    true
            );
            questionBody.addView(explanationTitle, buttonParams(dp(18)));
            TextView explanation = text(
                    question.getExplanation() == null ? "暂无题库解析" : question.getExplanation(),
                    isLargeQuestionFont() ? 17 : 15,
                    question.getExplanation() == null
                            ? R.color.brand_text_muted : R.color.brand_text_secondary,
                    false
            );
            explanation.setLineSpacing(dp(4), 1f);
            questionBody.addView(explanation, buttonParams(dp(7)));
        }

        LinearLayout actions = horizontal();
        MaterialButton favorite = outlineButton(
                progressStore.isFavorite(attempt.getQuestionBankId(), question.getId()) ? "★ 已收藏" : "☆ 收藏"
        );
        favorite.setOnClickListener(view -> {
            performConfiguredHaptic(view);
            progressStore.toggleFavorite(attempt.getQuestionBankId(), question.getId());
            if (FILTER_FAVORITE.equals(activeFilter)) {
                reviewIndex = Math.max(0, reviewIndex - 1);
            }
            showReview();
        });
        actions.addView(favorite, weightedButtonParams());
        MaterialButton ai = outlineButton("AI 讲解");
        ai.setOnClickListener(view -> AiExplanationDialogLauncher.show(
                this,
                attempt.getQuestionBankId(),
                question,
                item.getSelectedAnswers(),
                item.getCorrectAnswers(),
                item.isCorrect(),
                aiExecutor
        ));
        LinearLayout.LayoutParams aiParams = weightedButtonParams();
        aiParams.leftMargin = dp(10);
        actions.addView(ai, aiParams);
        questionBody.addView(actions, buttonParams(dp(18)));

        if (item.isWrong() && !item.isCorrected()) {
            MaterialButton correction = outlineButton("答案有误");
            correction.setTextColor(getColor(R.color.brand_error_dark));
            correction.setStrokeColor(ColorStateList.valueOf(getColor(R.color.brand_error)));
            correction.setOnClickListener(view -> confirmCorrection(question, item));
            questionBody.addView(correction, buttonParams(dp(10)));
        } else if (item.isCorrected()) {
            questionBody.addView(
                    text("已修正题库答案；本次考试分数仍按交卷快照计算。", 13, R.color.brand_text_muted, false),
                    buttonParams(dp(10))
            );
        }
        questionCard.addView(questionBody);
        content.addView(questionCard, cardParams());

        LinearLayout pager = horizontal();
        MaterialButton previous = outlineButton("上一题");
        previous.setEnabled(reviewIndex > 0);
        previous.setOnClickListener(view -> {
            reviewIndex--;
            showReview();
        });
        pager.addView(previous, weightedButtonParams());
        MaterialButton next = primaryButton(reviewIndex == filteredItems.size() - 1 ? "返回结果" : "下一题");
        next.setOnClickListener(view -> {
            if (reviewIndex == filteredItems.size() - 1) {
                showSummary();
            } else {
                reviewIndex++;
                showReview();
            }
        });
        LinearLayout.LayoutParams nextParams = weightedButtonParams();
        nextParams.leftMargin = dp(10);
        pager.addView(next, nextParams);
        content.addView(pager, buttonParams(dp(18)));

        MaterialButton retry = outlineButton("只练本次错题");
        retry.setEnabled(attempt.getWrongCount() > 0);
        retry.setOnClickListener(view -> startWrongRetry());
        content.addView(retry, buttonParams(dp(10)));
    }

    private void addFilters() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = horizontal();
        row.setPadding(0, 0, dp(6), 0);
        row.addView(filterButton("全部 " + attempt.getItems().size(), FILTER_ALL));
        row.addView(filterButton("答错 " + attempt.getWrongCount(), FILTER_WRONG));
        row.addView(filterButton("未作答 " + attempt.getUnansweredCount(), FILTER_UNANSWERED));
        row.addView(filterButton(
                "收藏 " + favoriteCount(),
                FILTER_FAVORITE
        ));
        scroller.addView(row);
        content.addView(scroller, buttonParams(dp(4)));
    }

    private MaterialButton filterButton(String label, String filter) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setTextSize(13);
        button.setCornerRadius(dp(18));
        button.setMinHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        boolean selected = filter.equals(activeFilter);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.brand_primary));
        button.setBackgroundTintList(ColorStateList.valueOf(
                getColor(selected ? R.color.brand_primary : R.color.brand_card)
        ));
        button.setStrokeColor(ColorStateList.valueOf(getColor(R.color.brand_primary_border)));
        button.setOnClickListener(view -> {
            activeFilter = filter;
            reviewIndex = 0;
            showReview();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private void rebuildFilter() {
        filteredItems.clear();
        Set<String> favorites = progressStore.getFavorites(attempt.getQuestionBankId());
        for (MockExamAttempt.Item item : attempt.getItems()) {
            if (!questionsById.containsKey(item.getQuestionId())) {
                continue;
            }
            if (FILTER_WRONG.equals(activeFilter) && !item.isWrong()) {
                continue;
            }
            if (FILTER_UNANSWERED.equals(activeFilter) && !item.getSelectedAnswers().isEmpty()) {
                continue;
            }
            if (FILTER_FAVORITE.equals(activeFilter) && !favorites.contains(item.getQuestionId())) {
                continue;
            }
            filteredItems.add(item);
        }
    }

    private int favoriteCount() {
        Set<String> favorites = progressStore.getFavorites(attempt.getQuestionBankId());
        int count = 0;
        for (MockExamAttempt.Item item : attempt.getItems()) {
            if (favorites.contains(item.getQuestionId())) {
                count++;
            }
        }
        return count;
    }

    private void addQuestionImage(LinearLayout parent, Question question) {
        String imageReference = question.getImage();
        if (imageReference == null || imageReference.trim().isEmpty()) {
            return;
        }
        try (InputStream inputStream = QuestionImageStore.open(
                this,
                attempt.getQuestionBankId(),
                imageReference
        )) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) {
                return;
            }
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(generalSettings.shouldOpenImagesFullscreen()
                    ? "题目图片，点击全屏查看"
                    : getString(R.string.question_image));
            if (generalSettings.shouldOpenImagesFullscreen()) {
                image.setClickable(true);
                image.setFocusable(true);
                image.setOnClickListener(view -> {
                    Intent intent = new Intent(this, QuestionImageViewerActivity.class);
                    intent.putExtra(
                            QuestionImageViewerActivity.EXTRA_QUESTION_BANK_ID,
                            attempt.getQuestionBankId()
                    );
                    intent.putExtra(
                            QuestionImageViewerActivity.EXTRA_IMAGE_REFERENCE,
                            imageReference
                    );
                    startActivity(intent);
                });
            }
            parent.addView(image, buttonParams(dp(isCompactDensity() ? 10 : 14)));
        } catch (Exception ignored) {
        }
    }

    private void addOptionRows(
            LinearLayout parent,
            SessionQuestion session,
            MockExamAttempt.Item item
    ) {
        for (Question.Option option : session.getDisplayOptions()) {
            boolean correct = item.getCorrectAnswers().contains(option.getKey());
            boolean selected = item.getSelectedAnswers().contains(option.getKey());
            int background = correct
                    ? Color.parseColor("#ECFDF5")
                    : selected ? Color.parseColor("#FFF1F2") : getColor(R.color.brand_card);
            int border = correct
                    ? getColor(R.color.brand_success)
                    : selected ? getColor(R.color.brand_error) : getColor(R.color.brand_border);
            TextView optionView = text(
                    session.displayText(option),
                    isLargeQuestionFont() ? 18 : 16,
                    R.color.brand_ink,
                    false
            );
            int verticalPadding = dp(isCompactDensity() ? 10 : 14);
            optionView.setPadding(dp(16), verticalPadding, dp(16), verticalPadding);
            optionView.setMinHeight(dp(48));
            GradientDrawable shape = rounded(background, dp(14));
            shape.setStroke(dp(correct || selected ? 2 : 1), border);
            optionView.setBackground(shape);
            parent.addView(optionView, buttonParams(dp(isCompactDensity() ? 6 : 9)));
        }
    }

    private boolean isLargeQuestionFont() {
        return generalSettings.getQuestionFontMode() == GeneralSettingsRules.QUESTION_FONT_LARGE;
    }

    private boolean isCompactDensity() {
        return generalSettings.getQuizDensity() == GeneralSettingsRules.QUIZ_DENSITY_COMPACT;
    }

    private void performConfiguredHaptic(View source) {
        if (generalSettings.shouldUseHapticFeedback()) {
            source.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void confirmCorrection(Question question, MockExamAttempt.Item item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.question_error)
                .setMessage(
                        "确认以你的作答作为题库正确答案吗？错题本记录会移除，"
                                + "但本次考试得分仍保持 " + attempt.getScore() + " 分。"
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("修改题库答案", (dialog, which) -> {
                    QuestionRepository.saveAnswerOverride(
                            this,
                            attempt.getQuestionBankId(),
                            question.getId(),
                            item.getSelectedAnswers()
                    );
                    progressStore.removeWrongQuestion(attempt.getQuestionBankId(), question.getId());
                    attempt.markCorrected(question.getId());
                    mockExamStore.save(attempt);
                    Toast.makeText(this, "已修正题库答案，考试分数未改变", Toast.LENGTH_SHORT).show();
                    showReview();
                })
                .show();
    }

    private void startWrongRetry() {
        if (attempt.getWrongCount() == 0) {
            Toast.makeText(this, "本次没有答错题", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, QuizActivity.class);
        intent.putExtra(QuizActivity.EXTRA_MODE, QuizActivity.MODE_MOCK_RETRY);
        intent.putExtra(QuizActivity.EXTRA_QUESTION_BANK, attempt.getQuestionBankId());
        intent.putExtra(QuizActivity.EXTRA_MOCK_ATTEMPT_ID, attempt.getId());
        startActivity(intent);
    }

    private String formatMappedAnswers(
            SessionQuestion session,
            Question question,
            List<String> answers
    ) {
        if (answers == null || answers.isEmpty()) {
            return "未作答";
        }
        List<String> formatted = new ArrayList<>();
        for (String answer : answers) {
            Question.Option matched = null;
            for (Question.Option option : question.getOptions()) {
                if (option.getKey().equals(answer)) {
                    matched = option;
                    break;
                }
            }
            if (matched == null) {
                formatted.add(answer);
            } else {
                formatted.add(session.getDisplayLabelForOriginalKey(answer) + ". " + matched.getText());
            }
        }
        return String.join("；", formatted);
    }

    private void addHeader(String title, String subtitle, View.OnClickListener backAction) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = outlineButton("‹");
        back.setTextSize(28);
        back.setMinWidth(0);
        back.setPadding(0, 0, 0, dp(3));
        back.setOnClickListener(backAction);
        row.addView(back, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout labels = vertical(0);
        labels.addView(text(title, 28, R.color.brand_ink, true));
        labels.addView(text(subtitle, 14, R.color.brand_text_secondary, false), buttonParams(dp(3)));
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        labelsParams.leftMargin = dp(14);
        row.addView(labels, labelsParams);
        LinearLayout.LayoutParams params = wrapParams();
        params.bottomMargin = dp(18);
        content.addView(row, params);
    }

    private TextView statLine(String label, int value, int colorRes) {
        TextView view = text(label + "  " + value + " 题", 16, colorRes, true);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private CardView card() {
        CardView card = new CardView(this);
        card.setRadius(dp(22));
        card.setCardElevation(dp(2));
        card.setCardBackgroundColor(getColor(R.color.brand_card));
        card.setUseCompatPadding(false);
        return card;
    }

    private MaterialButton primaryButton(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setCornerRadius(dp(15));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        return button;
    }

    private MaterialButton outlineButton(String label) {
        MaterialButton button = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(getColor(R.color.brand_primary));
        button.setCornerRadius(dp(15));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_card)));
        return button;
    }

    private LinearLayout vertical(int padding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView text(String value, int size, int colorRes, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(colorRes));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(14);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams(int topMargin) {
        LinearLayout.LayoutParams params = wrapParams();
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        return new LinearLayout.LayoutParams(0, dp(52), 1f);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    public void onBackPressed() {
        if (reviewMode) {
            showSummary();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        aiExecutor.shutdownNow();
        super.onDestroy();
    }
}
