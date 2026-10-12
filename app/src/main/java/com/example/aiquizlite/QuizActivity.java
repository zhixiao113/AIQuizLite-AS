package com.example.aiquizlite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.CompoundButton;
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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuizActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_QUESTION_BANK = "question_bank";
    public static final String EXTRA_MOCK_ATTEMPT_ID = "mock_attempt_id";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_TYPE = "type_practice";
    public static final String EXTRA_QUESTION_TYPE = "question_type";
    private String practiceType;
    public static final String MODE_DAILY_REVIEW = "daily_review";
    public static final String EXTRA_REVIEW_ID = "daily_review_id";
    public static final String EXTRA_REVIEW_IN_PROGRESS = "daily_review_in_progress";
    public static final String MODE_WRONG = "wrong";
    public static final String MODE_MEMORIZE = "memorize";
    public static final String MODE_MOCK = "mock";
    public static final String MODE_MOCK_RETRY = "mock_retry";
    public static final String MODE_FAVORITES = "favorites";
    private static final String STATE_MOCK_ATTEMPT_ID = "state_mock_attempt_id";
    private static final String STATE_MEMORIZE_INDEX = "memorize_index";

    private TextView modeTitleText;
    private TextView progressText;
    private QuestionJumpSheet questionJumpSheet;
    private boolean questionTransitionRunning;
    private TextView metaText;
    private TextView questionText;
    private TextView feedbackText;
    private ImageView questionImage;
    private LinearLayout optionsContainer;
    private MaterialButton primaryButton;
    private MaterialButton correctionButton;
    private MaterialButton favoriteButton;
    private MaterialButton aiExplanationButton;
    private View correctionPromptRow;
    private ScrollView contentScrollView;
    private LinearLayout quizRoot;
    private LinearLayout headerContent;
    private LinearLayout questionContent;
    private View headerCard;
    private View questionCard;

    private final List<SessionQuestion> sessionQuestions = new ArrayList<>();
    private final Map<String, List<String>> mockSelections = new HashMap<>();
    private final Map<String, List<MatchingData.Pair>> mockMatchingSelections = new HashMap<>();
    private final Map<String, ProgressStore.AnswerState> answerStates = new HashMap<>();
    private final Map<String, List<String>> draftAnswers = new HashMap<>();
    private final Map<String, List<MatchingData.Pair>> matchingDrafts = new HashMap<>();
    private MatchingQuestionView matchingView;
    private AlertDialog expandedMatchingDialog;
    private TextView matchingProgressText;
    private MaterialButton matchingCorrectButton;
    private LinearLayout matchingExplanationImages;

    private ProgressStore progressStore;
    private GeneralSettingsStore generalSettings;
    private MockExamStore mockExamStore;
    private MockExamAttempt mockAttempt;
    private ApiConfigStore apiConfigStore;
    private AiExplanationCache aiExplanationCache;
    private final OpenAiCompatibleClient aiClient = new OpenAiCompatibleClient();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final Handler autoAdvanceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutoAdvance;
    private String mode;
    private String questionBankId;
    private QuestionRepository.QuestionBank questionBank;
    private int currentIndex = 0;
    private int practiceAnsweredCount = 0;
    private int practiceCorrectCount = 0;
    private boolean answerRevealed = false;
    private boolean dailySessionFinished = false;
    private boolean wrongSessionFinished = false;
    private float swipeDownX;
    private float swipeDownY;
    private RadioGroup singleChoiceGroup;
    private final List<AppCompatCheckBox> multiChoiceBoxes = new ArrayList<>();
    private TextInputEditText fillAnswerEdit;
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
        favoriteButton = findViewById(R.id.favoriteButton);
        aiExplanationButton = findViewById(R.id.aiExplanationButton);
        correctionPromptRow = findViewById(R.id.correctionPromptRow);
        contentScrollView = findViewById(R.id.contentScrollView);
        quizRoot = findViewById(R.id.quizRoot);
        headerContent = findViewById(R.id.headerContent);
        questionContent = findViewById(R.id.questionContent);
        headerCard = findViewById(R.id.headerCard);
        questionCard = findViewById(R.id.questionCard);

        progressStore = new ProgressStore(this);
        generalSettings = new GeneralSettingsStore(this);
        mockExamStore = new MockExamStore(this);
        apiConfigStore = new ApiConfigStore(this);
        aiExplanationCache = new AiExplanationCache(this);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        practiceType = getIntent().getStringExtra(EXTRA_QUESTION_TYPE);
        questionBankId = getIntent().getStringExtra(EXTRA_QUESTION_BANK);
        questionBank = QuestionRepository.findQuestionBank(this, questionBankId);
        questionBankId = questionBank.getId();
        if (mode == null) {
            mode = MODE_DAILY;
        }
        if (MODE_TYPE.equals(mode) && !TypePracticeRules.TYPES.contains(practiceType)) {
            Toast.makeText(this, "请选择支持的练习题型", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        applyDisplayPreferences();
        applyMockKeepAwake();

        String mockAttemptId = savedInstanceState == null
                ? getIntent().getStringExtra(EXTRA_MOCK_ATTEMPT_ID)
                : savedInstanceState.getString(STATE_MOCK_ATTEMPT_ID);
        if (mockAttemptId != null) {
            mockAttempt = mockExamStore.load(mockAttemptId);
        }

        if (MODE_DAILY_REVIEW.equals(mode)) {
            if (!buildDailyReview()) { finish(); return; }
            if (savedInstanceState != null) {
                currentIndex = Math.max(0, Math.min(sessionQuestions.size() - 1,
                        savedInstanceState.getInt("daily_review_index", 0)));
            }
        } else {
            buildSession();
            if (MODE_MEMORIZE.equals(mode) && savedInstanceState != null) {
                currentIndex = Math.max(0, Math.min(sessionQuestions.size() - 1,
                        savedInstanceState.getInt(STATE_MEMORIZE_INDEX, 0)));
            }
        }
        if (MODE_TYPE.equals(mode)
                || MODE_DAILY.equals(mode) && generalSettings.shouldResumeDailyPractice()) {
            restoredDailySession = MODE_TYPE.equals(mode)
                    ? progressStore.getTypeSession(questionBankId, practiceType)
                    : progressStore.getDailySession(questionBankId);
            restoreDailySessionPosition();
        } else if (MODE_DAILY.equals(mode)) {
            progressStore.clearDailySession(questionBankId);
        }
        if (sessionQuestions.isEmpty()) {
            int emptyMessage = MODE_MOCK.equals(mode)
                    ? R.string.empty_mock_exam
                    : MODE_FAVORITES.equals(mode) ? R.string.empty_favorites
                    : MODE_WRONG.equals(mode) || MODE_MOCK_RETRY.equals(mode)
                    ? R.string.empty_wrong_book : R.string.empty_question_bank;
            Toast.makeText(this, getString(emptyMessage), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        primaryButton.setOnClickListener(view -> {
            performConfiguredHaptic(view);
            onPrimaryAction();
        });
        correctionButton.setOnClickListener(view -> confirmCorrectWrongAnswer());
        favoriteButton.setOnClickListener(view -> {
            performConfiguredHaptic(view);
            toggleCurrentFavorite();
        });
        aiExplanationButton.setOnClickListener(view -> showAiExplanation());
        progressText.setOnClickListener(view -> {
            performConfiguredHaptic(view);
            showQuestionJumpSheet();
        });
        renderQuestion();
    }

    private void showQuestionJumpSheet() {
        if (sessionQuestions.isEmpty() || questionTransitionRunning
                || (questionJumpSheet != null && questionJumpSheet.isShowing())) {
            return;
        }
        cancelPendingAutoAdvance();
        captureCurrentDraft();
        captureMockSelection();
        questionJumpSheet = new QuestionJumpSheet(
                this, currentIndex, sessionQuestions.size(),
                MODE_DAILY.equals(mode) || MODE_TYPE.equals(mode) || MODE_DAILY_REVIEW.equals(mode)
                        ? index -> answerStates.get(sessionQuestions.get(index).getQuestion().getId()) : null,
                targetIndex -> {
                    performConfiguredHaptic(progressText);
                    if (MODE_MOCK.equals(mode)) {
                        navigateMockToQuestion(targetIndex);
                    } else {
                        navigateToQuestion(targetIndex);
                    }
                },
                index -> String.valueOf(sessionQuestions.get(index).getQuestion().getIndex()));
        questionJumpSheet.setOnDismissListener(dialog -> questionJumpSheet = null);
        questionJumpSheet.show();
    }

    private boolean buildDailyReview() {
        if (!generalSettings.shouldReviewDailyHistory()) {
            Toast.makeText(this, R.string.daily_review_disabled, Toast.LENGTH_SHORT).show();
            return false;
        }
        DailyPracticeSnapshot snapshot;
        if (getIntent().getBooleanExtra(EXTRA_REVIEW_IN_PROGRESS, false)) {
            ProgressStore.DailySession session = progressStore.getDailySession(questionBankId);
            if (session == null) return false;
            List<Question> questions = new ArrayList<>(QuestionRepository.load(this, questionBankId));
            if (progressStore.isDailyOrderReversed(questionBankId)) Collections.reverse(questions);
            snapshot = new DailyPracticeSnapshot(questionBankId, questionBank.getDisplayName(),
                    System.currentTimeMillis(), questions, session.getAnswerStates());
        } else {
            snapshot = new DailyPracticeHistoryStore(this).load(getIntent().getStringExtra(EXTRA_REVIEW_ID));
        }
        if (snapshot == null || snapshot.questions.isEmpty()) {
            Toast.makeText(this, R.string.daily_review_unavailable, Toast.LENGTH_LONG).show();
            return false;
        }
        questionBankId = snapshot.bankId;
        for (Question question : snapshot.questions) sessionQuestions.add(new SessionQuestion(question, false));
        answerStates.putAll(snapshot.answers);
        modeTitleText.setText(getString(R.string.daily_review_title, snapshot.bankName));
        favoriteButton.setVisibility(View.GONE);
        return true;
    }

    private void applyDisplayPreferences() {
        int fontMode = generalSettings.getQuestionFontMode();
        boolean compact = generalSettings.getQuizDensity()
                == GeneralSettingsRules.QUIZ_DENSITY_COMPACT;

        questionText.setTextSize(GeneralSettingsRules.questionStemSizeSp(fontMode));
        feedbackText.setTextSize(fontMode == GeneralSettingsRules.QUESTION_FONT_LARGE ? 17
                : fontMode == GeneralSettingsRules.QUESTION_FONT_SMALL ? 14 : 15);
        quizRoot.setPadding(dp(compact ? 14 : 20), dp(compact ? 10 : 14),
                dp(compact ? 14 : 20), dp(compact ? 12 : 20));
        headerContent.setPadding(dp(compact ? 16 : 20), dp(compact ? 12 : 16),
                dp(compact ? 16 : 20), dp(compact ? 12 : 16));
        questionContent.setPadding(dp(compact ? 18 : 24), dp(compact ? 17 : 24),
                dp(compact ? 18 : 24), dp(compact ? 17 : 24));

        setTopMargin(headerCard, compact ? 6 : 8);
        setTopMargin(progressText, compact ? 2 : 4);
        setTopMargin(metaText, compact ? 0 : 2);
        metaText.setLineSpacing(dp(1), 1f);
        setTopMargin(contentScrollView, compact ? 8 : 10);
        setTopMargin(questionImage, compact ? 14 : 20);
        setTopMargin(optionsContainer, compact ? 16 : 24);
        setTopMargin(findViewById(R.id.feedbackCard), compact ? 14 : 20);
        setTopMargin(aiExplanationButton, compact ? 10 : 16);
        setTopMargin(primaryButton, compact ? 8 : 10);
    }

    private void setTopMargin(View view, int marginDp) {
        ViewGroup.LayoutParams rawParams = view.getLayoutParams();
        if (!(rawParams instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
        params.topMargin = dp(marginDp);
        view.setLayoutParams(params);
    }

    private void applyMockKeepAwake() {
        if (MODE_MOCK.equals(mode) && generalSettings.shouldKeepScreenOnDuringMock()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void performConfiguredHaptic(View source) {
        OperationFeedback.perform(source, generalSettings);
    }

    private void buildSession() {
        List<Question> allQuestions = new ArrayList<>(QuestionRepository.load(this, questionBankId));
        if (MODE_TYPE.equals(mode)) {
            for (Question question : TypePracticeRules.filter(allQuestions, practiceType)) {
                sessionQuestions.add(new SessionQuestion(question, false));
            }
            modeTitleText.setText(questionBank.getDisplayName() + " · "
                    + TypePracticeRules.label(practiceType) + "专项");
            return;
        }
        if ((MODE_DAILY.equals(mode) || MODE_MEMORIZE.equals(mode))
                && progressStore.isDailyOrderReversed(questionBankId)) {
            Collections.reverse(allQuestions);
        }
        if (MODE_WRONG.equals(mode)) {
            if (!restoreWrongSession(allQuestions)) {
                Map<String, Integer> wrongBook = progressStore.getWrongBook(questionBankId);
                for (Question question : allQuestions) {
                    if (wrongBook.containsKey(question.getId())) {
                        sessionQuestions.add(new SessionQuestion(question, false));
                    }
                }
            }
            modeTitleText.setText(questionBank.getDisplayName() + " · 错题本");
            return;
        }
        if (MODE_FAVORITES.equals(mode)) {
            Set<String> favorites = progressStore.getFavorites(questionBankId);
            for (Question question : allQuestions) {
                if (favorites.contains(question.getId())) {
                    sessionQuestions.add(new SessionQuestion(question, false));
                }
            }
            modeTitleText.setText(questionBank.getDisplayName() + " · 收藏题");
            return;
        }

        if (MODE_MOCK_RETRY.equals(mode)) {
            if (mockAttempt == null || !questionBankId.equals(mockAttempt.getQuestionBankId())) {
                return;
            }
            Set<String> wrongQuestionIds = new LinkedHashSet<>(mockAttempt.getWrongQuestionIds());
            for (Question question : allQuestions) {
                if (wrongQuestionIds.contains(question.getId())) {
                    sessionQuestions.add(new SessionQuestion(question, false));
                }
            }
            modeTitleText.setText(questionBank.getDisplayName() + " · 本次错题再练");
            return;
        }

        if (MODE_MOCK.equals(mode)) {
            if (mockAttempt == null || !questionBankId.equals(mockAttempt.getQuestionBankId())) {
                mockAttempt = MockExamAttempt.create(
                        questionBankId,
                        allQuestions,
                        new java.util.Random(),
                        System.currentTimeMillis(),
                        generalSettings.getMockQuestionLimit(),
                        generalSettings.shouldShuffleMockQuestions(),
                        generalSettings.shouldShuffleMockOptions()
                );
                mockExamStore.save(mockAttempt);
            }
            Map<String, Question> questionsById = new HashMap<>();
            for (Question question : allQuestions) {
                questionsById.put(question.getId(), question);
            }
            for (MockExamAttempt.Item item : mockAttempt.getItems()) {
                Question question = questionsById.get(item.getQuestionId());
                if (question == null) {
                    continue;
                }
                sessionQuestions.add(new SessionQuestion(
                        question,
                        item.getOptionKeyOrder(),
                        true
                ));
                if (!item.getSelectedAnswers().isEmpty()) {
                    mockSelections.put(question.getId(), item.getSelectedAnswers());
                }
                if (question.isMatching() && !item.getSelectedPairs().isEmpty()) {
                    mockMatchingSelections.put(question.getId(), item.getSelectedPairs());
                }
            }
            currentIndex = Math.min(
                    mockAttempt.getCurrentIndex(),
                    Math.max(0, sessionQuestions.size() - 1)
            );
            modeTitleText.setText(questionBank.getDisplayName() + " · 模拟考试");
            return;
        }
        if (MODE_MEMORIZE.equals(mode)) {
            for (Question question : allQuestions) {
                sessionQuestions.add(new SessionQuestion(question, false));
            }
            modeTitleText.setText(questionBank.getDisplayName() + " · 背题");
            return;
        }
        for (Question question : allQuestions) {
            sessionQuestions.add(new SessionQuestion(question, false));
        }

        modeTitleText.setText(questionBank.getDisplayName() + " · 日常刷题");
    }

    private boolean restoreWrongSession(List<Question> allQuestions) {
        ProgressStore.WrongSession session = progressStore.getWrongSession(questionBankId);
        if (session == null) {
            return false;
        }
        Map<String, Question> questionsById = new HashMap<>();
        for (Question question : allQuestions) {
            questionsById.put(question.getId(), question);
        }
        List<SessionQuestion> restored = new ArrayList<>();
        for (String questionId : session.getQuestionIds()) {
            Question question = questionsById.get(questionId);
            if (question != null) {
                restored.add(new SessionQuestion(question, false));
            }
        }
        if (restored.isEmpty()) {
            progressStore.clearWrongSession(questionBankId);
            return false;
        }
        sessionQuestions.addAll(restored);
        answerStates.clear();
        answerStates.putAll(session.getAnswerStates());
        draftAnswers.clear();
        draftAnswers.putAll(session.getDraftAnswers());
        matchingDrafts.clear();
        matchingDrafts.putAll(session.getMatchingDrafts());
        practiceAnsweredCount = session.getAnsweredCount();
        practiceCorrectCount = session.getCorrectCount();
        currentIndex = Math.max(0, Math.min(session.getCurrentIndex(), sessionQuestions.size() - 1));
        return true;
    }

    private void restoreDailySessionPosition() {
        if (restoredDailySession == null || sessionQuestions.isEmpty()) {
            return;
        }

        answerStates.clear();
        answerStates.putAll(restoredDailySession.getAnswerStates());
        draftAnswers.clear();
        draftAnswers.putAll(restoredDailySession.getDraftAnswers());
        matchingDrafts.clear();
        matchingDrafts.putAll(restoredDailySession.getMatchingDrafts());
        if (!restoredDailySession.getSelectedAnswers().isEmpty()
                && !restoredDailySession.getQuestionId().isEmpty()
                && !draftAnswers.containsKey(restoredDailySession.getQuestionId())) {
            draftAnswers.put(
                    restoredDailySession.getQuestionId(),
                    restoredDailySession.getSelectedAnswers()
            );
        }
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

        clearActivePracticeSession();
        restoredDailySession = null;
        answerStates.clear();
        draftAnswers.clear();
        matchingDrafts.clear();
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
        correctionPromptRow.setVisibility(View.GONE);
        optionsContainer.removeAllViews();
        multiChoiceBoxes.clear();
        singleChoiceGroup = null;
        fillAnswerEdit = null;
        matchingView = null;
        matchingProgressText = null;
        matchingCorrectButton = null;
        matchingExplanationImages = null;

        SessionQuestion sessionQuestion = sessionQuestions.get(currentIndex);
        Question question = sessionQuestion.getQuestion();
        applyQuestionCardSpacing(question.isMatching());

        progressText.setText("第 " + (currentIndex + 1) + " / " + sessionQuestions.size() + " 题");
        progressText.setContentDescription(getString(
                R.string.question_jump_open, currentIndex + 1, sessionQuestions.size()));
        String streakText = "";
        if (!MODE_DAILY_REVIEW.equals(mode) && !MODE_MEMORIZE.equals(mode)
                && progressStore.isInWrongBook(questionBankId, question.getId())) {
            int threshold = generalSettings.getWrongRemovalThreshold();
            streakText = threshold == GeneralSettingsRules.WRONG_REMOVAL_MANUAL
                    ? " | 错题本 · 手动移除"
                    : " | 错题本连对 " + progressStore.getStreak(questionBankId, question.getId())
                    + "/" + threshold;
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

        if (MODE_DAILY_REVIEW.equals(mode)) {
            ProgressStore.AnswerState state = answerStates.get(question.getId());
            if (state != null && state.isAnswered()) {
                if (question.isMatching()) matchingView.setSelectedPairs(state.getSelectedPairs());
                else applySelectedAnswers(state.getSelectedAnswers());
                String label = getString(state.isCorrected() ? R.string.daily_review_corrected
                        : state.isCorrect() ? R.string.daily_review_correct : R.string.daily_review_wrong);
                List<String> correct = state.isCorrected() ? state.getSelectedAnswers() : question.getAnswers();
                String userText = question.isMatching()
                        ? formatMatchingPairs(question, state.getSelectedPairs())
                        : formatAnswers(question, state.getSelectedAnswers());
                String correctText = question.isMatching()
                        ? formatMatchingPairs(question, state.isCorrected()
                                ? state.getSelectedPairs() : question.getMatching().getCorrectPairs())
                        : formatAnswers(question, correct);
                feedbackText.setText(getString(R.string.daily_review_feedback, label,
                        userText, correctText)
                        + (question.getExplanation() == null ? "" : "\n\n解析：\n" + question.getExplanation()));
                if (question.isMatching()) {
                    matchingView.setSubmitted(true);
                    matchingCorrectButton.setVisibility(View.VISIBLE);
                    showMatchingExplanationImages();
                }
            } else {
                feedbackText.setText(R.string.daily_review_unanswered);
            }
            if (feedbackCard instanceof CardView) {
                ((CardView) feedbackCard).setCardBackgroundColor(getColor(R.color.brand_primary_soft));
                feedbackCard.setVisibility(View.VISIBLE);
            }
            feedbackText.setTextColor(getColor(R.color.brand_ink));
            feedbackText.setVisibility(View.VISIBLE);
            aiExplanationButton.setVisibility(View.GONE);
            setInputsEnabled(false);
            primaryButton.setText(currentIndex == sessionQuestions.size() - 1
                    ? R.string.daily_review_back : R.string.next_question);
            contentScrollView.scrollTo(0, 0);
            return;
        }

        if (MODE_MOCK.equals(mode)) {
            List<String> selectedAnswers = mockSelections.get(question.getId());
            if (question.isMatching()) {
                List<MatchingData.Pair> pairs = mockMatchingSelections.get(question.getId());
                if (pairs != null) matchingView.setSelectedPairs(pairs);
            } else if (selectedAnswers != null) {
                applySelectedAnswers(selectedAnswers);
            }
            setInputsEnabled(true);
            updatePrimaryButton();
            updateCorrectionButton();
            updateFavoriteButton();
            updateAiExplanationButton();
            if (contentScrollView != null) {
                contentScrollView.scrollTo(0, 0);
            }
            return;
        }

        if (MODE_MEMORIZE.equals(mode)) {
            renderMemorizeQuestion(question);
            if (contentScrollView != null) {
                contentScrollView.scrollTo(0, 0);
            }
            return;
        }

        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state != null && state.isAnswered()) {
            if (question.isMatching()) matchingView.setSelectedPairs(state.getSelectedPairs());
            else applySelectedAnswers(state.getSelectedAnswers());
            answerRevealed = true;
            showPracticeFeedback(state.isCorrect(), state.getSelectedAnswers(), question, state.isCorrected());
        } else {
            if (question.isMatching()) {
                List<MatchingData.Pair> pairs = matchingDrafts.get(question.getId());
                if (pairs != null) matchingView.setSelectedPairs(pairs);
            } else {
                List<String> draft = draftAnswers.get(question.getId());
                if (draft != null && !draft.isEmpty()) applySelectedAnswers(draft);
            }
        }

        setInputsEnabled(!answerRevealed);
        updatePrimaryButton();
        updateCorrectionButton();
        updateFavoriteButton();
        updateAiExplanationButton();

        if (contentScrollView != null) {
            contentScrollView.scrollTo(0, 0);
        }
    }

    private void renderMemorizeQuestion(Question question) {
        List<String> correctAnswers = question.getAnswers();
        highlightCorrectOptions(question, correctAnswers);
        if (question.isMatching()) matchingCorrectButton.setVisibility(View.VISIBLE);
        if (question.isMatching()) showMatchingExplanationImages();
        setInputsEnabled(false);

        StringBuilder detail = new StringBuilder("正确答案：" + (question.isMatching()
                ? formatMatchingPairs(question, question.getMatching().getCorrectPairs())
                : formatAnswers(question, correctAnswers)));
        if (generalSettings.shouldShowSourceExplanation() && question.getExplanation() != null) {
            detail.append("\n\n解析：\n").append(question.getExplanation());
        }
        feedbackText.setText(detail.toString());
        View feedbackCard = findViewById(R.id.feedbackCard);
        if (feedbackCard instanceof CardView) {
            CardView card = (CardView) feedbackCard;
            card.setCardBackgroundColor(getColor(R.color.brand_primary_soft));
            card.setVisibility(View.VISIBLE);
        }
        feedbackText.setTextColor(getColor(R.color.brand_ink));
        feedbackText.setVisibility(View.VISIBLE);

        correctionPromptRow.setVisibility(View.GONE);
        aiExplanationButton.setVisibility(View.GONE);
        updateFavoriteButton();
        primaryButton.setText(currentIndex == sessionQuestions.size() - 1
                ? R.string.back_home : R.string.next_question);
    }

    private void highlightCorrectOptions(Question question, List<String> correctAnswers) {
        if (question.isMatching()) {
            matchingView.setSubmitted(true);
            matchingView.setShowCorrect(true);
            return;
        }
        int correctColor = getColor(R.color.brand_success);
        int inactiveColor = getColor(R.color.brand_text_secondary);
        if (question.isFillBlank()) {
            if (fillAnswerEdit != null) {
                fillAnswerEdit.setText(formatAnswers(question, correctAnswers));
                fillAnswerEdit.setTextColor(correctColor);
            }
            return;
        }
        if (singleChoiceGroup != null) {
            for (int i = 0; i < singleChoiceGroup.getChildCount(); i++) {
                markOptionCorrectness(
                        singleChoiceGroup.getChildAt(i), correctAnswers, correctColor, inactiveColor);
            }
            return;
        }
        for (AppCompatCheckBox checkBox : multiChoiceBoxes) {
            markOptionCorrectness(checkBox, correctAnswers, correctColor, inactiveColor);
        }
    }

    private void markOptionCorrectness(
            View optionView,
            List<String> correctAnswers,
            int correctColor,
            int inactiveColor
    ) {
        Object tag = optionView.getTag();
        boolean isCorrect = tag instanceof String && correctAnswers.contains(tag);
        if (optionView instanceof CompoundButton) {
            CompoundButton optionButton = (CompoundButton) optionView;
            optionButton.setButtonTintList(ColorStateList.valueOf(correctColor));
            optionButton.setChecked(isCorrect);
        }
        if (optionView instanceof TextView) {
            TextView optionText = (TextView) optionView;
            optionText.setTextColor(isCorrect ? correctColor : inactiveColor);
            if (isCorrect) {
                optionText.setTypeface(optionText.getTypeface(), Typeface.BOLD);
            }
        }
    }

    private void bindImage(String imageName) {
        if (imageName == null || imageName.isEmpty()) {
            questionImage.setVisibility(View.GONE);
            questionImage.setOnClickListener(null);
            questionImage.setClickable(false);
            return;
        }
        try {
            try (InputStream inputStream = QuestionImageStore.open(
                    this,
                    questionBankId,
                    imageName
            )) {
                questionImage.setImageBitmap(BitmapFactory.decodeStream(inputStream));
            }
            questionImage.setVisibility(View.VISIBLE);
            boolean fullscreenEnabled = generalSettings.shouldOpenImagesFullscreen();
            questionImage.setClickable(fullscreenEnabled);
            questionImage.setFocusable(fullscreenEnabled);
            questionImage.setContentDescription(fullscreenEnabled
                    ? "题目图片，点击全屏查看"
                    : getString(R.string.question_image));
            questionImage.setOnClickListener(fullscreenEnabled ? view -> {
                Intent intent = new Intent(this, QuestionImageViewerActivity.class);
                intent.putExtra(QuestionImageViewerActivity.EXTRA_QUESTION_BANK_ID, questionBankId);
                intent.putExtra(QuestionImageViewerActivity.EXTRA_IMAGE_REFERENCE, imageName);
                intent.putExtra(
                        QuestionImageViewerActivity.EXTRA_KEEP_SCREEN_ON,
                        MODE_MOCK.equals(mode) && generalSettings.shouldKeepScreenOnDuringMock()
                );
                startActivity(intent);
            } : null);
        } catch (Exception e) {
            questionImage.setVisibility(View.GONE);
            questionImage.setOnClickListener(null);
            questionImage.setClickable(false);
        }
    }

    private void bindOptions(SessionQuestion sessionQuestion) {
        Question question = sessionQuestion.getQuestion();
        if (question.isMatching()) {
            matchingProgressText = new TextView(this);
            matchingProgressText.setTextColor(getColor(R.color.brand_text_secondary));
            matchingProgressText.setTextSize(14);
            optionsContainer.addView(matchingProgressText, defaultLayoutParams());
            matchingView = new MatchingQuestionView(this, question.getMatching(),
                    matchingItemTextSize());
            matchingProgressText.setText("已完成 0/" + matchingView.getTargetCount() + " 项");
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            MaterialButton undo = new MaterialButton(this);
            undo.setText("撤销");
            undo.setOnClickListener(view -> matchingView.undo());
            actions.addView(undo, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            MaterialButton clear = new MaterialButton(this);
            clear.setText("清空");
            clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setMessage("清空本题所有连线？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("清空", (dialog, which) -> matchingView.clear())
                    .show());
            actions.addView(clear, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            MaterialButton expand = new MaterialButton(this);
            expand.setText("展开");
            expand.setContentDescription("展开连线工作区");
            expand.setOnClickListener(view -> showExpandedMatching(question));
            actions.addView(expand, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            optionsContainer.addView(actions, defaultLayoutParams());
            optionsContainer.addView(matchingView, defaultLayoutParams());
            matchingView.setOnSelectionChangedListener(() -> {
                matchingProgressText.setText("已完成 " + matchingView.getCompletedCount()
                        + "/" + matchingView.getTargetCount() + " 项");
                boolean editing = matchingView.isEditable();
                undo.setEnabled(editing && matchingView.canUndo());
                clear.setEnabled(editing && matchingView.getCompletedCount() > 0);
                updatePrimaryButton();
            });
            undo.setEnabled(false);
            clear.setEnabled(false);
            matchingCorrectButton = new MaterialButton(this);
            matchingCorrectButton.setText("查看正确连线");
            matchingCorrectButton.setVisibility(View.GONE);
            matchingCorrectButton.setOnClickListener(view -> {
                boolean show = !matchingCorrectButton.getText().toString().equals("返回我的连线");
                matchingView.setShowCorrect(show);
                matchingCorrectButton.setText(show ? "返回我的连线" : "查看正确连线");
            });
            optionsContainer.addView(matchingCorrectButton, defaultLayoutParams());
            if (!question.getExplanationImages().isEmpty()) {
                matchingExplanationImages = new LinearLayout(this);
                matchingExplanationImages.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < question.getExplanationImages().size(); i++) {
                    String reference = question.getExplanationImages().get(i);
                    MaterialButton imageButton = new MaterialButton(this);
                    imageButton.setText("查看解析图 " + (i + 1));
                    imageButton.setOnClickListener(view -> openQuestionImage(reference));
                    matchingExplanationImages.addView(imageButton, defaultLayoutParams());
                }
                matchingExplanationImages.setVisibility(View.GONE);
                optionsContainer.addView(matchingExplanationImages, defaultLayoutParams());
            }
        } else if (question.isFillBlank()) {
            TextInputLayout inputLayout = new TextInputLayout(this);
            inputLayout.setHint("请输入答案");
            inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

            fillAnswerEdit = new TextInputEditText(this);
            fillAnswerEdit.setTextSize(questionOptionTextSize());
            fillAnswerEdit.setTextColor(getColor(R.color.brand_ink));
            fillAnswerEdit.setMinLines(1);
            fillAnswerEdit.setMaxLines(3);
            fillAnswerEdit.setOnClickListener(this::performConfiguredHaptic);
            fillAnswerEdit.setInputType(
                    InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
            inputLayout.addView(fillAnswerEdit, new TextInputLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            optionsContainer.addView(inputLayout, defaultLayoutParams());
        } else if (question.isMultiChoice()) {
            for (Question.Option option : sessionQuestion.getDisplayOptions()) {
                AppCompatCheckBox checkBox = new AppCompatCheckBox(this);
                checkBox.setText(sessionQuestion.displayText(option));
                checkBox.setTag(option.getKey());
                checkBox.setTextSize(questionOptionTextSize());
                checkBox.setTextColor(getColor(R.color.brand_ink));
                applyOptionSpacing(checkBox);
                checkBox.setBackgroundResource(R.drawable.option_background);
                checkBox.setButtonTintList(getColorStateList(R.color.brand_primary));
                checkBox.setOnClickListener(this::performConfiguredHaptic);
                optionsContainer.addView(checkBox, optionLayoutParams());
                multiChoiceBoxes.add(checkBox);
            }
        } else {
            RadioGroup group = new RadioGroup(this);
            group.setOrientation(LinearLayout.VERTICAL);
            for (Question.Option option : sessionQuestion.getDisplayOptions()) {
                RadioButton button = new RadioButton(this);
                button.setId(View.generateViewId());
                button.setText(sessionQuestion.displayText(option));
                button.setTag(option.getKey());
                button.setTextSize(questionOptionTextSize());
                button.setTextColor(getColor(R.color.brand_ink));
                applyOptionSpacing(button);
                button.setBackgroundResource(R.drawable.option_background);
                button.setButtonTintList(getColorStateList(R.color.brand_primary));
                button.setOnClickListener(this::performConfiguredHaptic);
                group.addView(button, optionLayoutParams());
            }
            optionsContainer.addView(group, defaultLayoutParams());
            singleChoiceGroup = group;
        }
    }

    private void showMatchingExplanationImages() {
        if (matchingExplanationImages != null) matchingExplanationImages.setVisibility(View.VISIBLE);
    }

    private void showExpandedMatching(Question question) {
        if (matchingView == null) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView hint = new TextView(this);
        hint.setText(matchingView.isSubmitted()
                ? "已提交 · 红线为错误连接，绿线为正确连接"
                : "从一侧连接点拖向另一侧；也可依次点击两个连接点。提交前不显示对错。");
        hint.setTextColor(getColor(R.color.brand_text_secondary));
        hint.setTextSize(14);
        root.addView(hint, defaultLayoutParams());
        ScrollView scroll = new ScrollView(this);
        MatchingQuestionView expanded = new MatchingQuestionView(this, question.getMatching(),
                matchingItemTextSize(), matchingView.getSelectionState());
        if (matchingView.isSubmitted()) expanded.setSubmitted(true);
        expanded.setEditable(matchingView.isEditable());
        scroll.addView(expanded);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton undo = new MaterialButton(this);
        undo.setText(matchingView.isSubmitted() ? "查看标准连线" : "撤销");
        undo.setEnabled(expanded.isSubmitted() || expanded.isEditable() && expanded.canUndo());
        undo.setOnClickListener(view -> {
            if (matchingView.isSubmitted()) {
                boolean show = !"返回我的连线".contentEquals(undo.getText());
                expanded.setShowCorrect(show);
                undo.setText(show ? "返回我的连线" : "查看标准连线");
            } else expanded.undo();
        });
        actions.addView(undo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (matchingView.isEditable()) {
            MaterialButton clear = new MaterialButton(this);
            clear.setText("清空");
            clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setMessage("清空本题所有连线？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("清空", (dialog, which) -> expanded.clear()).show());
            actions.addView(clear, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            clear.setEnabled(expanded.getCompletedCount() > 0);
            expanded.setOnSelectionChangedListener(() -> {
                undo.setEnabled(expanded.canUndo());
                clear.setEnabled(expanded.getCompletedCount() > 0);
                hint.setText("已完成 " + expanded.getCompletedCount() + "/" + expanded.getTargetCount()
                        + " 项 · 返回题目后提交答案，提交前不显示对错。");
            });
        }
        root.addView(actions, defaultLayoutParams());
        expandedMatchingDialog = new AlertDialog.Builder(this)
                .setTitle("连线工作区")
                .setView(root)
                .setPositiveButton("返回题目", null)
                .create();
        expandedMatchingDialog.setOnDismissListener(dialog -> expandedMatchingDialog = null);
        expandedMatchingDialog.show();
        if (expandedMatchingDialog.getWindow() != null) {
            expandedMatchingDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void openQuestionImage(String reference) {
        Intent intent = new Intent(this, QuestionImageViewerActivity.class);
        intent.putExtra(QuestionImageViewerActivity.EXTRA_QUESTION_BANK_ID, questionBankId);
        intent.putExtra(QuestionImageViewerActivity.EXTRA_IMAGE_REFERENCE, reference);
        startActivity(intent);
    }

    private LinearLayout.LayoutParams defaultLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(isCompactDensity() ? 8 : 12);
        return params;
    }

    private LinearLayout.LayoutParams optionLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(isCompactDensity() ? 8 : 12);
        return params;
    }

    private float questionOptionTextSize() {
        return GeneralSettingsRules.questionOptionSizeSp(generalSettings.getQuestionFontMode());
    }

    private int matchingItemTextSize() {
        return GeneralSettingsRules.matchingItemSizeSp(generalSettings.getQuestionFontMode());
    }

    private void applyQuestionCardSpacing(boolean matching) {
        boolean compact = isCompactDensity();
        int horizontal = matching ? (compact ? 10 : 12) : (compact ? 18 : 24);
        int vertical = compact ? 17 : 24;
        questionContent.setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical));
        setTopMargin(optionsContainer, matching ? (compact ? 8 : 12) : (compact ? 16 : 24));
    }

    private boolean isCompactDensity() {
        return generalSettings.getQuizDensity() == GeneralSettingsRules.QUIZ_DENSITY_COMPACT;
    }

    private void applyOptionSpacing(TextView option) {
        int horizontal = dp(isCompactDensity() ? 14 : 16);
        int vertical = dp(isCompactDensity() ? 9 : 13);
        option.setPadding(horizontal, vertical, horizontal, vertical);
        option.setMinHeight(dp(48));
    }

    private void onPrimaryAction() {
        cancelPendingAutoAdvance();
        if (MODE_DAILY_REVIEW.equals(mode)) {
            if (currentIndex == sessionQuestions.size() - 1) finish();
            else { currentIndex++; renderQuestion(); }
            return;
        }
        if (MODE_MEMORIZE.equals(mode)) {
            if (currentIndex == sessionQuestions.size() - 1) {
                finish();
            } else {
                currentIndex++;
                renderQuestion();
            }
            return;
        }
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

        if (question.isMatching()) {
            List<MatchingData.Pair> pairs = matchingView.getSelectedPairs();
            if (!question.getMatching().isComplete(pairs)) {
                Toast.makeText(this, "请先连接全部右侧项目", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean correct = question.getMatching().isCorrect(pairs);
            progressStore.recordResult(questionBankId, question.getId(), correct,
                    generalSettings.shouldAutoAddWrongQuestion(),
                    generalSettings.getWrongRemovalThreshold());
            answerStates.put(question.getId(), new ProgressStore.AnswerState(
                    question.getId(), Collections.emptyList(), true, correct, false, pairs));
            matchingDrafts.remove(question.getId());
            practiceAnsweredCount++;
            if (correct) practiceCorrectCount++;
            answerRevealed = true;
            showPracticeFeedback(correct, Collections.emptyList(), question, false);
            updatePrimaryButton();
            updateCorrectionButton();
            updateAiExplanationButton();
            return;
        }

        List<String> selectedAnswers = collectSelectedAnswers();
        if (selectedAnswers.isEmpty()) {
            int message = question.isFillBlank() ? R.string.no_fill_answer : R.string.no_selection;
            Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean correct = isCorrectAnswer(question, selectedAnswers);
        progressStore.recordResult(
                questionBankId,
                question.getId(),
                correct,
                generalSettings.shouldAutoAddWrongQuestion(),
                generalSettings.getWrongRemovalThreshold()
        );
        answerStates.put(question.getId(), new ProgressStore.AnswerState(
                question.getId(),
                selectedAnswers,
                true,
                correct,
                false
        ));
        draftAnswers.remove(question.getId());
        practiceAnsweredCount++;
        if (correct) {
            practiceCorrectCount++;
        }
        answerRevealed = true;
        showPracticeFeedback(correct, selectedAnswers, question, false);
        updatePrimaryButton();
        updateCorrectionButton();
        updateAiExplanationButton();
        scheduleAutoAdvanceIfEnabled(correct);
    }

    private void handleMockAction() {
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        if (question.isMatching()) {
            List<MatchingData.Pair> pairs = matchingView.getSelectedPairs();
            if (pairs.isEmpty()) mockMatchingSelections.remove(question.getId());
            else mockMatchingSelections.put(question.getId(), pairs);
            mockAttempt.setMatchingSelection(question.getId(), pairs);
        } else {
            List<String> selectedAnswers = collectSelectedAnswers();
            if (selectedAnswers.isEmpty()) mockSelections.remove(question.getId());
            else mockSelections.put(question.getId(), selectedAnswers);
            mockAttempt.setSelection(question.getId(), selectedAnswers);
        }
        mockAttempt.setCurrentIndex(currentIndex);
        mockExamStore.save(mockAttempt);

        if (currentIndex == sessionQuestions.size() - 1) {
            if (generalSettings.shouldWarnBeforeSubmittingUnansweredMock()
                    && mockAttempt.getUnansweredCount() > 0) {
                showUnansweredMockWarning(mockAttempt.getUnansweredCount());
            } else {
                finishMockExam();
            }
        } else {
            currentIndex += 1;
            mockAttempt.setCurrentIndex(currentIndex);
            mockExamStore.save(mockAttempt);
            renderQuestion();
        }
    }

    private void showUnansweredMockWarning(int unansweredCount) {
        new AlertDialog.Builder(this)
                .setTitle("还有未作答题目")
                .setMessage("仍有 " + unansweredCount + " 题未作答。你可以取消交卷，并左右滑动返回检查。")
                .setNegativeButton("继续作答", null)
                .setPositiveButton("仍然交卷", (dialog, which) -> {
                    performConfiguredHaptic(primaryButton);
                    finishMockExam();
                })
                .show();
    }

    private void scheduleAutoAdvanceIfEnabled(boolean correct) {
        boolean enabled = correct
                ? generalSettings.shouldAutoAdvanceAfterCorrect()
                : generalSettings.shouldAutoAdvanceAfterWrong();
        if (!enabled) {
            return;
        }
        int delaySeconds = generalSettings.getAutoAdvanceDelaySeconds();
        if (delaySeconds == GeneralSettingsRules.AUTO_ADVANCE_DISABLED) {
            return;
        }
        pendingAutoAdvance = () -> {
            pendingAutoAdvance = null;
            moveNextOrFinishPractice();
        };
        autoAdvanceHandler.postDelayed(
                pendingAutoAdvance,
                delaySeconds * 1000L
        );
    }

    private void cancelPendingAutoAdvance() {
        if (pendingAutoAdvance != null) {
            autoAdvanceHandler.removeCallbacks(pendingAutoAdvance);
            pendingAutoAdvance = null;
        }
    }

    private void showPracticeFeedback(
            boolean correct,
            List<String> selectedAnswers,
            Question question,
            boolean corrected
    ) {
        if (question.isMatching()) {
            ProgressStore.AnswerState state = answerStates.get(question.getId());
            List<MatchingData.Pair> pairs = state == null
                    ? Collections.emptyList() : state.getSelectedPairs();
            matchingView.setSubmitted(true);
            matchingCorrectButton.setVisibility(View.VISIBLE);
            showMatchingExplanationImages();
            int correctPairs = question.getMatching().countCorrect(pairs);
            String detail = "你的连线：\n" + formatMatchingPairs(question, pairs)
                    + "\n正确 " + correctPairs + " 项，错误 "
                    + (question.getMatching().getRightItems().size() - correctPairs) + " 项";
            if (generalSettings.shouldShowSourceExplanation() && question.getExplanation() != null) {
                detail += "\n解析：\n" + question.getExplanation();
            }
            feedbackText.setText((correct ? "✓ 本题答对" : "✕ 本题答错") + "\n" + detail);
            View card = findViewById(R.id.feedbackCard);
            if (card instanceof CardView) {
                ((CardView) card).setCardBackgroundColor(getColor(
                        correct ? R.color.brand_success : R.color.brand_error));
                card.setVisibility(View.VISIBLE);
            }
            feedbackText.setTextColor(getColor(R.color.brand_ink));
            feedbackText.setVisibility(View.VISIBLE);
            return;
        }
        String resultLabel = correct ? "✓ 回答正确" : "✕ 回答错误";
        String selected = "你的答案：" + formatAnswers(question, selectedAnswers);
        List<String> effectiveCorrectAnswers = corrected ? selectedAnswers : question.getAnswers();
        String correctAnswer = "正确答案：" + formatAnswers(question, effectiveCorrectAnswers);
        String detail = "";
        if (corrected) {
            detail = question.isFillBlank()
                    ? "\n已将该答案加入可接受答案，不计入错题本。"
                    : "\n已修正题库答案，不计入错题本。";
        } else if (!correct) {
            detail = progressStore.isInWrongBook(questionBankId, question.getId())
                    ? "\n已加入或保留在错题本。"
                    : "\n本次未加入错题本。";
        }
        if (generalSettings.shouldShowSourceExplanation() && question.getExplanation() != null) {
            detail += "\n\n解析：\n" + question.getExplanation();
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
        cancelPendingAutoAdvance();
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state == null || !state.isAnswered() || state.isCorrect() || state.isCorrected()) {
            return;
        }
        List<String> selectedAnswers = state.getSelectedAnswers();

        if (question.isMatching()) {
            List<MatchingData.Pair> pairs = state.getSelectedPairs();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.question_error)
                    .setMessage("确认将本题标准连线改为你的作答？\n\n"
                            + formatMatchingPairs(question, pairs)
                            + "\n\n本次记录会标为已修正并移出错题本。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("修改题库答案", (dialog, which) -> {
                        QuestionRepository.saveMatchingAnswerOverride(this, questionBankId,
                                question, pairs);
                        answerStates.put(question.getId(), new ProgressStore.AnswerState(
                                question.getId(), Collections.emptyList(), true, true, true, pairs));
                        progressStore.removeWrongQuestion(questionBankId, question.getId());
                        practiceCorrectCount++;
                        refreshCurrentQuestion();
                        renderQuestion();
                    }).show();
            return;
        }

        if (question.isFillBlank()) {
            confirmAppendFillBlankAnswer(question, selectedAnswers);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.question_error)
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

    private void confirmAppendFillBlankAnswer(Question question, List<String> selectedAnswers) {
        String candidate = selectedAnswers.isEmpty()
                ? ""
                : AnswerMatcher.cleanFillBlankForStorage(selectedAnswers.get(0));
        if (candidate.isEmpty()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.question_error)
                .setMessage(
                        "确认将“" + candidate + "”加入这道填空题的可接受答案吗？原答案会保留。"
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("加入可接受答案", (dialog, which) -> {
                    List<String> acceptedAnswers = new ArrayList<>(question.getAnswers());
                    if (!AnswerMatcher.containsEquivalentAnswer(acceptedAnswers, candidate)) {
                        acceptedAnswers.add(candidate);
                    }
                    QuestionRepository.saveAnswerOverride(
                            this,
                            questionBankId,
                            question.getId(),
                            acceptedAnswers
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
                    Toast.makeText(this, "已加入可接受答案", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshCurrentQuestion() {
        String currentId = sessionQuestions.get(currentIndex).getQuestion().getId();
        for (Question latest : QuestionRepository.load(this, questionBankId)) {
            if (latest.getId().equals(currentId)) {
                sessionQuestions.set(currentIndex, new SessionQuestion(latest, false));
                return;
            }
        }
    }

    private void moveNextOrFinishPractice() {
        cancelPendingAutoAdvance();
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state == null || !state.isAnswered()) {
            return;
        }

        int nextIndex = PracticeNavigationRules.nextIndex(
                currentIndex, sessionQuestions.size(), index -> {
                    ProgressStore.AnswerState answer = answerStates.get(
                            sessionQuestions.get(index).getQuestion().getId());
                    return answer != null && answer.isAnswered();
                });
        if (nextIndex == -1) {
            finishPractice();
            return;
        }
        if (nextIndex < currentIndex) {
            Toast.makeText(this, R.string.question_jump_continue_unanswered, Toast.LENGTH_SHORT).show();
        }
        currentIndex = nextIndex;
        renderQuestion();
    }

    private void finishPractice() {
        if (MODE_TYPE.equals(mode)) {
            if (dailySessionFinished) return;
            dailySessionFinished = true;
            clearActivePracticeSession();
        } else if (MODE_DAILY.equals(mode)) {
            if (dailySessionFinished) return;
            dailySessionFinished = true;
            long timestamp = System.currentTimeMillis();
            String detailId = "";
            if (generalSettings.shouldReviewDailyHistory()) {
                List<Question> questions = new ArrayList<>();
                for (SessionQuestion item : sessionQuestions) questions.add(item.getQuestion());
                DailyPracticeSnapshot snapshot = new DailyPracticeSnapshot(questionBankId,
                        questionBank.getDisplayName(), timestamp, questions, answerStates);
                try {
                    new DailyPracticeHistoryStore(this).save(snapshot);
                    detailId = snapshot.id;
                } catch (Exception error) {
                    Toast.makeText(this, R.string.daily_review_save_failed, Toast.LENGTH_LONG).show();
                }
            }
            progressStore.appendDailyPracticeRecord(
                    questionBankId,
                    new ProgressStore.DailyPracticeRecord(
                            timestamp,
                            practiceCorrectCount,
                            practiceAnsweredCount - practiceCorrectCount,
                            detailId
                    )
            );
            progressStore.clearDailySession(questionBankId);
        } else if (MODE_WRONG.equals(mode)) {
            if (wrongSessionFinished) return;
            wrongSessionFinished = true;
            progressStore.clearWrongSession(questionBankId);
        }
        new AlertDialog.Builder(this)
                .setTitle("本轮完成")
                .setMessage("答对 " + practiceCorrectCount + " 题，答错 " + (practiceAnsweredCount - practiceCorrectCount) + " 题。")
                .setPositiveButton(R.string.back_home, (dialog, which) -> finish())
                .show();
    }

    private void finishMockExam() {
        captureMockSelection();
        for (SessionQuestion sessionQuestion : sessionQuestions) {
            Question question = sessionQuestion.getQuestion();
            List<String> selected = mockSelections.get(question.getId());
            MockExamAttempt.Item item = mockAttempt.findItem(question.getId());
            if (item != null && item.isAnswered()
                    && (question.isMatching() || (selected != null && !selected.isEmpty()))) {
                progressStore.recordResult(
                        questionBankId,
                        question.getId(),
                        item != null && item.isCorrect(),
                        generalSettings.shouldAutoAddWrongQuestion(),
                        generalSettings.getWrongRemovalThreshold()
                );
            }
        }
        mockAttempt.markCompleted(System.currentTimeMillis());
        mockExamStore.save(mockAttempt);
        Intent intent = new Intent(this, MockResultActivity.class);
        intent.putExtra(EXTRA_MOCK_ATTEMPT_ID, mockAttempt.getId());
        startActivity(intent);
        finish();
    }

    private void updatePrimaryButton() {
        if (MODE_MEMORIZE.equals(mode) || MODE_DAILY_REVIEW.equals(mode)) {
            primaryButton.setText(currentIndex == sessionQuestions.size() - 1
                    ? MODE_MEMORIZE.equals(mode) ? R.string.back_home : R.string.daily_review_back
                    : R.string.next_question);
            primaryButton.setEnabled(true);
            return;
        }
        if (MODE_MOCK.equals(mode)) {
            primaryButton.setText(currentIndex == sessionQuestions.size() - 1
                    ? R.string.submit_exam
                    : R.string.next_question);
            primaryButton.setEnabled(true);
            return;
        }

        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        boolean answered = state != null && state.isAnswered();
        primaryButton.setText(answered ? R.string.next_question : R.string.submit_answer);
        primaryButton.setEnabled(!question.isMatching() || answered || matchingView == null
                || matchingView.isComplete());
    }

    private void updateCorrectionButton() {
        if (MODE_MOCK.equals(mode) || MODE_MEMORIZE.equals(mode)) {
            correctionPromptRow.setVisibility(View.GONE);
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        boolean canCorrect = state != null && state.isAnswered() && !state.isCorrect() && !state.isCorrected();
        correctionPromptRow.setVisibility(canCorrect ? View.VISIBLE : View.GONE);
    }

    private void updateFavoriteButton() {
        if (sessionQuestions.isEmpty()) {
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        boolean favorite = progressStore.isFavorite(questionBankId, question.getId());
        favoriteButton.setText(favorite ? R.string.favorite_star_selected : R.string.favorite_star);
        favoriteButton.setContentDescription(getString(
                favorite ? R.string.remove_question_from_favorites : R.string.favorite_question
        ));
    }

    private void updateAiExplanationButton() {
        if (MODE_MOCK.equals(mode) || MODE_MEMORIZE.equals(mode) || sessionQuestions.isEmpty()) {
            aiExplanationButton.setVisibility(View.GONE);
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        boolean answered = state != null && state.isAnswered();
        aiExplanationButton.setVisibility(answered ? View.VISIBLE : View.GONE);
    }

    private void showAiExplanation() {
        cancelPendingAutoAdvance();
        if (MODE_MOCK.equals(mode) || MODE_MEMORIZE.equals(mode) || sessionQuestions.isEmpty()) {
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state == null || !state.isAnswered()) {
            return;
        }
        ApiConfig config = apiConfigStore.getConfig();
        if (!config.isComplete()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.ai_configuration_required)
                    .setMessage(R.string.ai_configuration_required_detail)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.go_to_settings, (dialog, which) ->
                            startActivity(new Intent(this, SettingsActivity.class)))
                    .show();
            return;
        }

        List<String> selectedAnswers = state.getSelectedAnswers();
        boolean correct = state.isCorrect() || state.isCorrected();
        String cacheKey = AiExplanationCache.keyFor(
                questionBankId, question, selectedAnswers, question.getAnswers(),
                state.getSelectedPairs(), question.isMatching()
                        ? (state.isCorrected() ? state.getSelectedPairs()
                                : question.getMatching().getCorrectPairs()) : Collections.emptyList(),
                correct, config);
        AiExplanation cached = aiExplanationCache.get(cacheKey);
        showAiExplanationDialog(question, state, config, cacheKey, cached);
    }

    private void showAiExplanationDialog(
            Question question,
            ProgressStore.AnswerState answerState,
            ApiConfig config,
            String cacheKey,
            AiExplanation cached
    ) {
        View content = getLayoutInflater().inflate(R.layout.dialog_ai_explanation, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();

        TextView subtitle = content.findViewById(R.id.aiDialogSubtitle);
        View loadingContainer = content.findViewById(R.id.aiLoadingContainer);
        View errorContainer = content.findViewById(R.id.aiErrorContainer);
        View explanationContainer = content.findViewById(R.id.aiContentContainer);
        TextView errorText = content.findViewById(R.id.aiErrorText);
        MaterialButton retryButton = content.findViewById(R.id.aiRetryButton);
        MaterialButton checkSettingsButton = content.findViewById(R.id.aiCheckSettingsButton);
        MaterialButton copyButton = content.findViewById(R.id.aiCopyButton);
        MaterialButton regenerateButton = content.findViewById(R.id.aiRegenerateButton);
        AiExplanation[] displayed = new AiExplanation[]{cached};

        content.findViewById(R.id.aiDialogCloseButton).setOnClickListener(view -> dialog.dismiss());
        checkSettingsButton.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        copyButton.setOnClickListener(view -> {
            if (displayed[0] == null) {
                return;
            }
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.ai_explanation),
                    displayed[0].toDisplayText()
            ));
            Toast.makeText(this, R.string.ai_explanation_copied, Toast.LENGTH_SHORT).show();
        });

        Runnable request = () -> requestAiExplanation(
                dialog,
                content,
                subtitle,
                loadingContainer,
                errorContainer,
                explanationContainer,
                errorText,
                question,
                answerState,
                config,
                cacheKey,
                displayed
        );
        retryButton.setOnClickListener(view -> request.run());
        regenerateButton.setOnClickListener(view -> request.run());

        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (cached == null) {
                request.run();
            } else {
                subtitle.setText(R.string.ai_dialog_cached);
                renderAiExplanation(content, question, cached);
                loadingContainer.setVisibility(View.GONE);
                errorContainer.setVisibility(View.GONE);
                explanationContainer.setVisibility(View.VISIBLE);
            }
        });
        dialog.show();
    }

    private void requestAiExplanation(
            AlertDialog dialog,
            View content,
            TextView subtitle,
            View loadingContainer,
            View errorContainer,
            View explanationContainer,
            TextView errorText,
            Question question,
            ProgressStore.AnswerState answerState,
            ApiConfig config,
            String cacheKey,
            AiExplanation[] displayed
    ) {
        subtitle.setText(R.string.ai_dialog_generating);
        loadingContainer.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);
        explanationContainer.setVisibility(View.GONE);

        List<String> selectedAnswers = answerState.getSelectedAnswers();
        boolean correct = answerState.isCorrect() || answerState.isCorrected();
        String formattedUserAnswer = formatAnswers(question, selectedAnswers);
        List<String> effectiveCorrectAnswers = answerState.isCorrected()
                ? selectedAnswers
                : question.getAnswers();
        String formattedCorrectAnswer = formatAnswers(question, effectiveCorrectAnswers);
        String systemPrompt = AiExplanationPromptBuilder.systemPrompt();
        String userPrompt = AiExplanationPromptBuilder.userPrompt(
                question,
                selectedAnswers,
                correct,
                formattedUserAnswer,
                formattedCorrectAnswer,
                answerState.getSelectedPairs(),
                question.isMatching() ? (answerState.isCorrected()
                        ? answerState.getSelectedPairs() : question.getMatching().getCorrectPairs())
                        : Collections.emptyList()
        );

        aiExecutor.execute(() -> {
            try {
                AiExplanation explanation = aiClient.generateExplanation(
                        config,
                        systemPrompt,
                        userPrompt
                );
                aiExplanationCache.put(cacheKey, explanation);
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) {
                        return;
                    }
                    displayed[0] = explanation;
                    subtitle.setText(R.string.ai_explanation);
                    renderAiExplanation(content, question, explanation);
                    loadingContainer.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                    explanationContainer.setVisibility(View.VISIBLE);
                });
            } catch (OpenAiCompatibleClient.AiClientException e) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) {
                        return;
                    }
                    subtitle.setText(R.string.ai_dialog_failed);
                    errorText.setText(e.getMessage());
                    loadingContainer.setVisibility(View.GONE);
                    explanationContainer.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void renderAiExplanation(
            View content,
            Question question,
            AiExplanation explanation
    ) {
        bindAiSection(
                content.findViewById(R.id.aiConclusionSection),
                content.findViewById(R.id.aiConclusionText),
                explanation.getConclusion()
        );
        bindAiSection(
                content.findViewById(R.id.aiReasoningSection),
                content.findViewById(R.id.aiReasoningText),
                explanation.getReasoning()
        );
        bindAiSection(
                content.findViewById(R.id.aiMistakeSection),
                content.findViewById(R.id.aiMistakeText),
                explanation.getCommonMistake()
        );
        bindAiSection(
                content.findViewById(R.id.aiMemorySection),
                content.findViewById(R.id.aiMemoryText),
                explanation.getMemoryTip()
        );
        View originalCard = content.findViewById(R.id.originalExplanationCard);
        TextView originalText = content.findViewById(R.id.originalExplanationText);
        if (question.getExplanation() == null) {
            originalCard.setVisibility(View.GONE);
        } else {
            originalText.setText(question.getExplanation());
            originalCard.setVisibility(View.VISIBLE);
        }
    }

    private void bindAiSection(View section, TextView textView, String text) {
        if (text == null || text.trim().isEmpty()) {
            section.setVisibility(View.GONE);
        } else {
            textView.setText(text.trim());
            section.setVisibility(View.VISIBLE);
        }
    }

    private void toggleCurrentFavorite() {
        cancelPendingAutoAdvance();
        if (sessionQuestions.isEmpty()) {
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        boolean favorite = progressStore.toggleFavorite(questionBankId, question.getId());
        updateFavoriteButton();
        Toast.makeText(this, favorite ? "已加入收藏题" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        cancelPendingAutoAdvance();
        if (questionJumpSheet != null) {
            questionJumpSheet.dismiss();
        }
        if (contentScrollView != null) {
            contentScrollView.animate().cancel();
        }
        aiExecutor.shutdownNow();
        super.onDestroy();
    }

    private List<String> collectSelectedAnswers() {
        List<String> answers = new ArrayList<>();
        if (fillAnswerEdit != null) {
            CharSequence text = fillAnswerEdit.getText();
            String answer = text == null ? "" : text.toString().trim();
            if (!answer.isEmpty()) {
                answers.add(answer);
            }
            return answers;
        }
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

    private boolean isCorrectAnswer(Question question, List<String> selectedAnswers) {
        if (question.isFillBlank()) {
            String answer = selectedAnswers == null || selectedAnswers.isEmpty()
                    ? null
                    : selectedAnswers.get(0);
            return AnswerMatcher.matchesFillBlank(answer, question.getAnswers());
        }
        return sameAnswers(selectedAnswers, question.getAnswers());
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

    private String formatAnswers(Question question, List<String> answers) {
        if (!question.isFillBlank()) {
            return joinAnswers(answers);
        }
        StringBuilder builder = new StringBuilder();
        for (String answer : answers) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(answer);
        }
        return builder.toString();
    }

    private String formatMatchingPairs(Question question, List<MatchingData.Pair> pairs) {
        if (pairs == null || pairs.isEmpty()) return "未作答";
        MatchingData data = question.getMatching();
        StringBuilder result = new StringBuilder();
        for (MatchingData.Pair pair : pairs) {
            if (result.length() > 0) result.append("\n");
            result.append(data.rightText(pair.getRightId())).append(" → ")
                    .append(data.leftText(pair.getLeftId()));
        }
        return result.toString();
    }

    private void setInputsEnabled(boolean enabled) {
        if (matchingView != null) matchingView.setEditable(enabled);
        if (fillAnswerEdit != null) {
            fillAnswerEdit.setEnabled(enabled);
        }
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
        if (fillAnswerEdit != null) {
            fillAnswerEdit.setText(selectedAnswers.isEmpty() ? "" : selectedAnswers.get(0));
            return;
        }
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
        cancelPendingAutoAdvance();
        if (targetIndex < 0 || targetIndex >= sessionQuestions.size()
                || targetIndex == currentIndex || questionTransitionRunning) {
            return;
        }
        animateToQuestion(targetIndex);
    }

    private void animateToQuestion(int targetIndex) {
        captureCurrentDraft();
        if (contentScrollView == null) {
            currentIndex = targetIndex;
            renderQuestion();
            return;
        }
        int direction = targetIndex > currentIndex ? 1 : -1;
        questionTransitionRunning = true;
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
                            .withEndAction(() -> questionTransitionRunning = false)
                            .start();
                })
                .start();
    }

    private void saveDailySessionProgress() {
        if ((!MODE_DAILY.equals(mode) && !MODE_TYPE.equals(mode))
                || sessionQuestions.isEmpty() || currentIndex >= sessionQuestions.size()) {
            return;
        }
        if (MODE_DAILY.equals(mode) && !generalSettings.shouldResumeDailyPractice()) {
            clearActivePracticeSession();
            return;
        }
        if (dailySessionFinished) {
            clearActivePracticeSession();
            return;
        }

        captureCurrentDraft();
        Question currentQuestion = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.DailySession session = new ProgressStore.DailySession(
                currentQuestion.getId(),
                currentIndex,
                practiceAnsweredCount,
                practiceCorrectCount,
                answerRevealed,
                collectSelectedAnswers(),
                answerStates,
                draftAnswers,
                matchingDrafts
        );
        if (MODE_TYPE.equals(mode)) progressStore.saveTypeSession(questionBankId, practiceType, session);
        else progressStore.saveDailySession(questionBankId, session);
    }

    private void clearActivePracticeSession() {
        if (MODE_TYPE.equals(mode)) progressStore.clearTypeSession(questionBankId, practiceType);
        else progressStore.clearDailySession(questionBankId);
    }

    private void saveWrongSessionProgress() {
        if (!MODE_WRONG.equals(mode) || sessionQuestions.isEmpty() || currentIndex >= sessionQuestions.size()) {
            return;
        }
        if (wrongSessionFinished) {
            progressStore.clearWrongSession(questionBankId);
            return;
        }

        captureCurrentDraft();
        List<String> questionIds = new ArrayList<>();
        for (SessionQuestion item : sessionQuestions) {
            questionIds.add(item.getQuestion().getId());
        }
        progressStore.saveWrongSession(questionBankId, new ProgressStore.WrongSession(
                questionIds,
                currentIndex,
                practiceAnsweredCount,
                practiceCorrectCount,
                answerRevealed,
                answerStates,
                draftAnswers,
                matchingDrafts
        ));
    }

    private void captureCurrentDraft() {
        if (MODE_DAILY_REVIEW.equals(mode) || MODE_MEMORIZE.equals(mode)) return;
        if (sessionQuestions.isEmpty() || currentIndex < 0 || currentIndex >= sessionQuestions.size()) {
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        ProgressStore.AnswerState state = answerStates.get(question.getId());
        if (state != null && state.isAnswered()) {
            draftAnswers.remove(question.getId());
            matchingDrafts.remove(question.getId());
            return;
        }
        if (question.isMatching()) {
            List<MatchingData.Pair> pairs = matchingView.getSelectedPairs();
            if (pairs.isEmpty()) matchingDrafts.remove(question.getId());
            else matchingDrafts.put(question.getId(), new ArrayList<>(pairs));
            return;
        }
        List<String> currentAnswers = collectSelectedAnswers();
        if (currentAnswers.isEmpty()) {
            draftAnswers.remove(question.getId());
        } else {
            draftAnswers.put(question.getId(), new ArrayList<>(currentAnswers));
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!sessionQuestions.isEmpty()
                && (matchingView == null || !matchingView.isDragging())
                && (expandedMatchingDialog == null || !expandedMatchingDialog.isShowing())
                && (questionJumpSheet == null || !questionJumpSheet.isShowing())) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeDownX = event.getRawX();
                    swipeDownY = event.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - swipeDownX;
                    float dy = Math.abs(event.getRawY() - swipeDownY);
                    if (Math.abs(dx) > dp(72) && Math.abs(dx) > dy * 1.4f) {
                        int targetIndex = dx < 0 ? currentIndex + 1 : currentIndex - 1;
                        if (MODE_MOCK.equals(mode)) {
                            navigateMockToQuestion(targetIndex);
                        } else {
                            navigateToQuestion(targetIndex);
                        }
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void navigateMockToQuestion(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= sessionQuestions.size()) {
            return;
        }
        captureMockSelection();
        currentIndex = targetIndex;
        mockAttempt.setCurrentIndex(currentIndex);
        mockExamStore.save(mockAttempt);
        renderQuestion();
    }

    @Override
    protected void onPause() {
        cancelPendingAutoAdvance();
        captureMockSelection();
        saveDailySessionProgress();
        saveWrongSessionProgress();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        saveDailySessionProgress();
        if (MODE_DAILY_REVIEW.equals(mode)) outState.putInt("daily_review_index", currentIndex);
        if (MODE_MEMORIZE.equals(mode)) outState.putInt(STATE_MEMORIZE_INDEX, currentIndex);
        captureMockSelection();
        if (mockAttempt != null) {
            outState.putString(STATE_MOCK_ATTEMPT_ID, mockAttempt.getId());
        }
        super.onSaveInstanceState(outState);
    }

    private void captureMockSelection() {
        if (!MODE_MOCK.equals(mode)
                || mockAttempt == null
                || mockAttempt.isCompleted()
                || sessionQuestions.isEmpty()
                || currentIndex < 0
                || currentIndex >= sessionQuestions.size()) {
            return;
        }
        Question question = sessionQuestions.get(currentIndex).getQuestion();
        if (question.isMatching()) {
            List<MatchingData.Pair> pairs = matchingView.getSelectedPairs();
            if (pairs.isEmpty()) mockMatchingSelections.remove(question.getId());
            else mockMatchingSelections.put(question.getId(), pairs);
            mockAttempt.setMatchingSelection(question.getId(), pairs);
            mockAttempt.setCurrentIndex(currentIndex);
            mockExamStore.save(mockAttempt);
            return;
        }
        List<String> selectedAnswers = collectSelectedAnswers();
        if (selectedAnswers.isEmpty()) {
            mockSelections.remove(question.getId());
        } else {
            mockSelections.put(question.getId(), selectedAnswers);
        }
        mockAttempt.setSelection(question.getId(), selectedAnswers);
        mockAttempt.setCurrentIndex(currentIndex);
        mockExamStore.save(mockAttempt);
    }
}
