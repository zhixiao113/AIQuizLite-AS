package com.example.aiquizlite;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.cardview.widget.CardView;

import com.google.android.material.button.MaterialButton;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QuizActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_QUESTION_BANK = "question_bank";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_WRONG = "wrong";
    public static final String MODE_MOCK = "mock";

    private TextView modeTitleText;
    private TextView progressText;
    private TextView metaText;
    private TextView questionText;
    private TextView feedbackText;
    private ImageView questionImage;
    private LinearLayout optionsContainer;
    private MaterialButton primaryButton;
    private MaterialButton correctionButton;
    private ScrollView contentScrollView;

    private final List<SessionQuestion> sessionQuestions = new ArrayList<>();
    private final Map<String, List<String>> mockSelections = new HashMap<>();
    private final Map<String, ProgressStore.AnswerState> answerStates = new HashMap<>();

    private ProgressStore progressStore;
    private String mode;
    private String questionBankId;
    private QuestionRepository.QuestionBank questionBank;
    private int currentIndex = 0;
    private int practiceAnsweredCount = 0;
    private int practiceCorrectCount = 0;
    private boolean answerRevealed = false;
    private boolean dailySessionFinished = false;
    private float swipeDownX;
    private float swipeDownY;
    private RadioGroup singleChoiceGroup;
    private final List<AppCompatCheckBox> multiChoiceBoxes = new ArrayList<>();
    private ProgressStore.DailySession restoredDailySession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz);

        modeTitleText = findViewById(R.id.modeTitleText);
        progressText = findViewById(R.id.progressText);
        metaText = findViewById(R.id.metaText);
        questionText = findViewById(R.id.questionText);
        feedbackText = findViewById(R.id.feedbackText);
        questionImage = findViewById(R.id.questionImage);
        optionsContainer = findViewById(R.id.optionsContainer);
        primaryButton = findViewById(R.id.primaryButton);
        correctionButton = findViewById(R.id.correctionButton);
        contentScrollView = findViewById(R.id.contentScrollView);

        progressStore = new ProgressStore(this);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        questionBankId = getIntent().getStringExtra(EXTRA_QUESTION_BANK);
        questionBank = QuestionRepository.findQuestionBank(this, questionBankId);
        questionBankId = questionBank.getId();
        if (mode == null) {
            mode = MODE_DAILY;
        }

        buildSession();
        if (MODE_DAILY.equals(mode)) {
            restoredDailySession = progressStore.getDailySession(questionBankId);
            restoreDailySessionPosition();
        }
        if (sessionQuestions.isEmpty()) {
            Toast.makeText(this, getString(R.string.empty_wrong_book), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        primaryButton.setOnClickListener(view -> onPrimaryAction());
        correctionButton.setOnClickListener(view -> confirmCorrectWrongAnswer());
        renderQuestion();
    }

    private void buildSession() {
        List<Question> allQuestions = new ArrayList<>(QuestionRepository.load(this, questionBankId));
        if (MODE_DAILY.equals(mode) && progressStore.isDailyOrderReversed(questionBankId)) {
            Collections.reverse(allQuestions);
        }
        if (MODE_WRONG.equals(mode)) {
            Map<String, Integer> wrongBook = progressStore.getWrongBook(questionBankId);
            for (Question question : allQuestions) {
                if (wrongBook.containsKey(question.getId())) {
                    sessionQuestions.add(new SessionQuestion(question, false));
                }
            }
            modeTitleText.setText(questionBank.getDisplayName() + " · 错题本");
            return;
        }

        boolean shuffleOptions = MODE_MOCK.equals(mode);
        if (MODE_MOCK.equals(mode)) {
            Collections.shuffle(allQuestions);
        }
        for (Question question : allQuestions) {
            sessionQuestions.add(new SessionQuestion(question, shuffleOptions));
        }

        modeTitleText.setText(questionBank.getDisplayName() + (MODE_MOCK.equals(mode) ? " · 模拟考试" : " · 日常刷题"));
    }

    private void restoreDailySessionPosition() {
        if (restoredDailySession == null || sessionQuestions.isEmpty()) {
            return;
        }

        answerStates.clear();
        answerStates.putAll(restoredDailySession.getAnswerStates());
        practiceAnsweredCount = restoredDailySession.getAnsweredCount();
        practiceCorrectCount = restoredDailySession.getCorrectCount();

        int restoredIndex = restoredDailySession.getCurrentIndex();
        String restoredQuestionId = restoredDailySession.getQuestionId();
        if (restoredIndex >= 0 && restoredIndex < sessionQuestions.size()) {
            String questionId = sessionQuestions.get(restoredIndex).getQuestion().getId();
            if (questionId.equals(restoredQuestionId)) {
                currentIndex = restoredIndex;
                return;
            }
        }

        for (int i = 0; i < sessionQuestions.size(); i++) {
            if (sessionQuestions.get(i).getQuestion().getId().equals(restoredQuestionId)) {
                currentIndex = i;
                return;
            }
        }

        progressStore.clearDailySession(questionBankId);
        restoredDailySession = null;
        answerStates.clear();
        practiceAnsweredCount = 0;
        practiceCorrectCount = 0;
    }

    private void renderQuestion() {
        answerRevealed = false;
        feedbackText.setVisibility(View.GONE);
        View feedbackCard = findViewById(R.id.feedbackCard);
        if (feedbackCard != null) {
            feedbackCard.setVisibility(View.GONE);
        }
        correctionButton.setVisibility(View.GONE);
        optionsContainer.removeAllViews();
        multiChoiceBoxes.clear();
        singleChoiceGroup = null;

        SessionQuestion sessionQuestion = sessionQuestions.get(currentIndex);
        Question question = sessionQuestion.getQuestion();

        progressText.setText("第 " + (currentIndex + 1) + " / " + sessionQuestions.size() + " 题");
        String streakText = "";
        if (progressStore.isInWrongBook(questionBankId, question.getId())) {
            streakText = " | 错题本连对 " + progressStore.getStreak(questionBankId, question.getId()) + "/3";
        }
        metaText.setText(
                question.getPaper()
                        + " · 原卷第 "
                        + question.getNumberInPaper()
                        + " 题 · "
                        + question.typeLabel()
                        + streakText
        );
        questionText.setText(question.getIndex() + ". " + question.getStem());

        bindImage(question.getImage());
        bindOptions(sessionQuestion);

        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state != null && state.isAnswered()) {
            applySelectedAnswers(state.getSelectedAnswers());
            answerRevealed = true;
            showPracticeFeedback(state.isCorrect(), state.getSelectedAnswers(), question, state.isCorrected());
        } else if (restoredDailySession != null
                && question.getId().equals(restoredDailySession.getQuestionId())
                && !restoredDailySession.getSelectedAnswers().isEmpty()) {
            applySelectedAnswers(restoredDailySession.getSelectedAnswers());
        }

        setInputsEnabled(!answerRevealed);
        updatePrimaryButton();
        updateCorrectionButton();

        if (contentScrollView != null) {
            contentScrollView.scrollTo(0, 0);
        }
    }

    private void bindImage(String imageName) {
        if (imageName == null || imageName.isEmpty()) {
            questionImage.setVisibility(View.GONE);
            return;
        }
        try {
            InputStream inputStream = getAssets().open("question_images/" + imageName);
            questionImage.setImageBitmap(BitmapFactory.decodeStream(inputStream));
            inputStream.close();
            questionImage.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            questionImage.setVisibility(View.GONE);
        }
    }

    private void bindOptions(SessionQuestion sessionQuestion) {
        Question question = sessionQuestion.getQuestion();
        if (question.isMultiChoice()) {
            for (Question.Option option : sessionQuestion.getDisplayOptions()) {
                AppCompatCheckBox checkBox = new AppCompatCheckBox(this);
                checkBox.setText(option.displayText());
                checkBox.setTag(option.getKey());
                checkBox.setTextSize(16);
                checkBox.setTextColor(getColor(R.color.brand_ink));
                checkBox.setPadding(24, 24, 24, 24);
                checkBox.setBackgroundResource(R.drawable.option_background);
                checkBox.setButtonTintList(getColorStateList(R.color.brand_primary));
                optionsContainer.addView(checkBox, optionLayoutParams());
                multiChoiceBoxes.add(checkBox);
            }
        } else {
            RadioGroup group = new RadioGroup(this);
            group.setOrientation(LinearLayout.VERTICAL);
            for (Question.Option option : sessionQuestion.getDisplayOptions()) {
                RadioButton button = new RadioButton(this);
                button.setId(View.generateViewId());
                button.setText(option.displayText());
                button.setTag(option.getKey());
                button.setTextSize(16);
                button.setTextColor(getColor(R.color.brand_ink));
                button.setPadding(24, 24, 24, 24);
                button.setBackgroundResource(R.drawable.option_background);
                button.setButtonTintList(getColorStateList(R.color.brand_primary));
                group.addView(button, optionLayoutParams());
            }
            optionsContainer.addView(group, defaultLayoutParams());
            singleChoiceGroup = group;
        }
    }

    private LinearLayout.LayoutParams defaultLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = 16;
        return params;
    }

    private LinearLayout.LayoutParams optionLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = 20;
        return params;
    }

    private void onPrimaryAction() {
        if (MODE_MOCK.equals(mode)) {
            handleMockAction();
            return;
        }

        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState existingState = answerStates.get(question.getId());
        if (existingState != null && existingState.isAnswered()) {
            moveNextOrFinishPractice();
            return;
        }

        List<String> selectedAnswers = collectSelectedAnswers();
        if (selectedAnswers.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_selection), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean correct = sameAnswers(selectedAnswers, question.getAnswers());
        progressStore.recordResult(questionBankId, question.getId(), correct);
        answerStates.put(question.getId(), new ProgressStore.AnswerState(
                question.getId(),
                selectedAnswers,
                true,
                correct,
                false
        ));
        practiceAnsweredCount = Math.max(practiceAnsweredCount, currentIndex + 1);
        if (correct) {
            practiceCorrectCount++;
        }
        answerRevealed = true;
        showPracticeFeedback(correct, selectedAnswers, question, false);
        updatePrimaryButton();
        updateCorrectionButton();
    }

    private void handleMockAction() {
        List<String> selectedAnswers = collectSelectedAnswers();
        if (selectedAnswers.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_selection), Toast.LENGTH_SHORT).show();
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        mockSelections.put(question.getId(), selectedAnswers);

        if (currentIndex == sessionQuestions.size() - 1) {
            finishMockExam();
        } else {
            currentIndex += 1;
            renderQuestion();
        }
    }

    private void showPracticeFeedback(
            boolean correct,
            List<String> selectedAnswers,
            Question question,
            boolean corrected
    ) {
        String resultLabel = correct ? "✓ 回答正确" : "✕ 回答错误";
        String selected = "你的答案：" + joinAnswers(selectedAnswers);
        List<String> effectiveCorrectAnswers = corrected ? selectedAnswers : question.getAnswers();
        String correctAnswer = "正确答案：" + joinAnswers(effectiveCorrectAnswers);
        String detail = "";
        if (corrected) {
            detail = "\n已修正题库答案，不计入错题本。";
        } else if (!correct) {
            detail = "\n已加入或保留在错题本。";
        }

        feedbackText.setText(resultLabel + "\n" + selected + "\n" + correctAnswer + detail);

        View feedbackCard = findViewById(R.id.feedbackCard);
        if (feedbackCard instanceof CardView) {
            CardView card = (CardView) feedbackCard;
            card.setCardBackgroundColor(getColor(correct ? R.color.brand_success : R.color.brand_error));
            feedbackText.setTextColor(Color.WHITE);
            card.setVisibility(View.VISIBLE);
        }

        feedbackText.setVisibility(View.VISIBLE);
        setInputsEnabled(false);
    }

    private void confirmCorrectWrongAnswer() {
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state == null || !state.isAnswered() || state.isCorrect() || state.isCorrected()) {
            return;
        }
        List<String> selectedAnswers = state.getSelectedAnswers();

        new AlertDialog.Builder(this)
                .setTitle("答案有误")
                .setMessage(
                        "确认将题库中的正确答案从 "
                                + joinAnswers(question.getAnswers())
                                + " 修改为 "
                                + joinAnswers(selectedAnswers)
                                + " 吗？该题不会计入错题本。"
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("修改题库答案", (dialog, which) -> {
                    QuestionRepository.saveAnswerOverride(
                            this,
                            questionBankId,
                            question.getId(),
                            selectedAnswers
                    );
                    answerStates.put(question.getId(), new ProgressStore.AnswerState(
                            question.getId(),
                            selectedAnswers,
                            true,
                            true,
                            true
                    ));
                    progressStore.removeWrongQuestion(questionBankId, question.getId());
                    practiceCorrectCount++;
                    renderQuestion();
                    Toast.makeText(this, "已修正题库答案", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void moveNextOrFinishPractice() {
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state == null || !state.isAnswered()) {
            return;
        }

        if (currentIndex == sessionQuestions.size() - 1) {
            finishPractice();
            return;
        }

        currentIndex += 1;
        renderQuestion();
    }

    private void finishPractice() {
        if (MODE_DAILY.equals(mode)) {
            dailySessionFinished = true;
            progressStore.appendDailyPracticeRecord(
                    questionBankId,
                    new ProgressStore.DailyPracticeRecord(
                            System.currentTimeMillis(),
                            practiceCorrectCount,
                            practiceAnsweredCount - practiceCorrectCount
                    )
            );
            progressStore.clearDailySession(questionBankId);
        }
        new AlertDialog.Builder(this)
                .setTitle("本轮完成")
                .setMessage("答对 " + practiceCorrectCount + " 题，答错 " + (practiceAnsweredCount - practiceCorrectCount) + " 题。")
                .setPositiveButton(R.string.back_home, (dialog, which) -> finish())
                .show();
    }

    private void finishMockExam() {
        int correctCount = 0;
        for (SessionQuestion sessionQuestion : sessionQuestions) {
            Question question = sessionQuestion.getQuestion();
            List<String> selected = mockSelections.get(question.getId());
            boolean correct = sameAnswers(selected, question.getAnswers());
            progressStore.recordResult(questionBankId, question.getId(), correct);
            if (correct) {
                correctCount++;
            }
        }

        int total = sessionQuestions.size();
        int score = Math.round(correctCount * 100f / total);
        new AlertDialog.Builder(this)
                .setTitle("模拟考试结果")
                .setMessage("得分 " + score + "\n答对 " + correctCount + " 题，答错 " + (total - correctCount) + " 题。")
                .setPositiveButton(R.string.back_home, (dialog, which) -> finish())
                .show();
    }

    private void updatePrimaryButton() {
        if (MODE_MOCK.equals(mode)) {
            primaryButton.setText(currentIndex == sessionQuestions.size() - 1
                    ? R.string.submit_exam
                    : R.string.next_question);
            return;
        }

        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        boolean answered = state != null && state.isAnswered();
        primaryButton.setText(answered ? R.string.next_question : R.string.submit_answer);
    }

    private void updateCorrectionButton() {
        if (MODE_MOCK.equals(mode)) {
            correctionButton.setVisibility(View.GONE);
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        boolean canCorrect = state != null && state.isAnswered() && !state.isCorrect() && !state.isCorrected();
        correctionButton.setVisibility(canCorrect ? View.VISIBLE : View.GONE);
    }

    private List<String> collectSelectedAnswers() {
        List<String> answers = new ArrayList<>();
        if (singleChoiceGroup != null) {
            int checkedId = singleChoiceGroup.getCheckedRadioButtonId();
            if (checkedId != -1) {
                View checkedView = singleChoiceGroup.findViewById(checkedId);
                Object tag = checkedView.getTag();
                if (tag instanceof String) {
                    answers.add((String) tag);
                }
            }
            return answers;
        }

        for (AppCompatCheckBox checkBox : multiChoiceBoxes) {
            if (checkBox.isChecked()) {
                Object tag = checkBox.getTag();
                if (tag instanceof String) {
                    answers.add((String) tag);
                }
            }
        }
        Collections.sort(answers);
        return answers;
    }

    private boolean sameAnswers(List<String> selected, List<String> expected) {
        if (selected == null || expected == null) {
            return false;
        }
        Set<String> selectedSet = new LinkedHashSet<>(selected);
        Set<String> expectedSet = new LinkedHashSet<>(expected);
        return selectedSet.equals(expectedSet);
    }

    private String joinAnswers(List<String> answers) {
        StringBuilder builder = new StringBuilder();
        for (String answer : answers) {
            builder.append(answer);
        }
        return builder.toString();
    }

    private void setInputsEnabled(boolean enabled) {
        if (singleChoiceGroup != null) {
            for (int i = 0; i < singleChoiceGroup.getChildCount(); i++) {
                singleChoiceGroup.getChildAt(i).setEnabled(enabled);
            }
        }
        for (AppCompatCheckBox checkBox : multiChoiceBoxes) {
            checkBox.setEnabled(enabled);
        }
    }

    private void applySelectedAnswers(List<String> selectedAnswers) {
        if (singleChoiceGroup != null) {
            for (int i = 0; i < singleChoiceGroup.getChildCount(); i++) {
                View optionView = singleChoiceGroup.getChildAt(i);
                Object tag = optionView.getTag();
                if (tag instanceof String && selectedAnswers.contains(tag)) {
                    singleChoiceGroup.check(optionView.getId());
                    return;
                }
            }
            return;
        }

        for (AppCompatCheckBox checkBox : multiChoiceBoxes) {
            Object tag = checkBox.getTag();
            checkBox.setChecked(tag instanceof String && selectedAnswers.contains(tag));
        }
    }

    private void navigateToQuestion(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= sessionQuestions.size()) {
            return;
        }
        int maxAccessibleIndex = Math.min(sessionQuestions.size() - 1, practiceAnsweredCount);
        if (targetIndex > maxAccessibleIndex) {
            Toast.makeText(this, "只能切换到已答题目和当前题目", Toast.LENGTH_SHORT).show();
            return;
        }
        animateToQuestion(targetIndex);
    }

    private void animateToQuestion(int targetIndex) {
        if (contentScrollView == null) {
            currentIndex = targetIndex;
            renderQuestion();
            return;
        }
        int direction = targetIndex > currentIndex ? 1 : -1;
        float width = getResources().getDisplayMetrics().widthPixels;
        contentScrollView.animate()
                .translationX(-direction * width * 0.28f)
                .alpha(0.35f)
                .setDuration(110)
                .withEndAction(() -> {
                    currentIndex = targetIndex;
                    renderQuestion();
                    contentScrollView.setTranslationX(direction * width * 0.28f);
                    contentScrollView.setAlpha(0.35f);
                    contentScrollView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(150)
                            .start();
                })
                .start();
    }

    private void saveDailySessionProgress() {
        if (!MODE_DAILY.equals(mode) || sessionQuestions.isEmpty() || currentIndex >= sessionQuestions.size()) {
            return;
        }
        if (dailySessionFinished) {
            progressStore.clearDailySession(questionBankId);
            return;
        }

        Question currentQuestion = sessionQuestions.get(currentIndex).getQuestion();
        progressStore.saveDailySession(questionBankId, new ProgressStore.DailySession(
                currentQuestion.getId(),
                currentIndex,
                practiceAnsweredCount,
                practiceCorrectCount,
                answerRevealed,
                collectSelectedAnswers(),
                answerStates
        ));
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!MODE_MOCK.equals(mode) && !sessionQuestions.isEmpty()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeDownX = event.getRawX();
                    swipeDownY = event.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - swipeDownX;
                    float dy = Math.abs(event.getRawY() - swipeDownY);
                    if (Math.abs(dx) > dp(72) && Math.abs(dx) > dy * 1.4f) {
                        navigateToQuestion(dx < 0 ? currentIndex + 1 : currentIndex - 1);
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onPause() {
        saveDailySessionProgress();
        super.onPause();
    }
}
