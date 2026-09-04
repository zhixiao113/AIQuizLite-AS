package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class GeneralSettingsStore {
    private static final String PREF_NAME = "general_settings_v1";
    private static final String KEY_RESUME_DAILY = "resume_daily";
    private static final String KEY_AUTO_ADD_WRONG = "auto_add_wrong";
    private static final String KEY_WRONG_REMOVAL_THRESHOLD = "wrong_removal_threshold";
    private static final String KEY_CORRECT_AUTO_ADVANCE = "correct_auto_advance";
    private static final String KEY_WRONG_AUTO_ADVANCE = "wrong_auto_advance";
    private static final String KEY_AUTO_ADVANCE_DELAY = "auto_advance_delay";
    private static final String KEY_SHOW_SOURCE_EXPLANATION = "show_source_explanation";
    private static final String KEY_MOCK_QUESTION_LIMIT = "mock_question_limit";
    private static final String KEY_MOCK_SHUFFLE_QUESTIONS = "mock_shuffle_questions";
    private static final String KEY_MOCK_SHUFFLE_OPTIONS = "mock_shuffle_options";
    private static final String KEY_MOCK_WARN_UNANSWERED = "mock_warn_unanswered";
    private static final String KEY_DISCOVER_MANUAL_ZIP = "discover_manual_zip";
    private static final String KEY_DOWNLOAD_TREE_URI = "download_tree_uri";
    private static final String KEY_DOWNLOAD_TREE_LABEL = "download_tree_label";
    private static final String KEY_QUESTION_FONT_MODE = "question_font_mode";
    private static final String KEY_QUIZ_DENSITY = "quiz_density";
    private static final String KEY_IMAGE_FULLSCREEN = "image_fullscreen";
    private static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";
    private static final String KEY_MOCK_KEEP_SCREEN_ON = "mock_keep_screen_on";

    private final SharedPreferences preferences;

    public GeneralSettingsStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean shouldResumeDailyPractice() {
        return preferences.getBoolean(KEY_RESUME_DAILY, true);
    }

    public void setResumeDailyPractice(boolean enabled) {
        preferences.edit().putBoolean(KEY_RESUME_DAILY, enabled).apply();
    }

    public boolean shouldAutoAddWrongQuestion() {
        return preferences.getBoolean(KEY_AUTO_ADD_WRONG, true);
    }

    public void setAutoAddWrongQuestion(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_ADD_WRONG, enabled).apply();
    }

    public int getWrongRemovalThreshold() {
        return GeneralSettingsRules.normalizeWrongRemovalThreshold(
                preferences.getInt(KEY_WRONG_REMOVAL_THRESHOLD, 3)
        );
    }

    public void setWrongRemovalThreshold(int value) {
        preferences.edit().putInt(
                KEY_WRONG_REMOVAL_THRESHOLD,
                GeneralSettingsRules.normalizeWrongRemovalThreshold(value)
        ).apply();
    }

    public boolean shouldAutoAdvanceAfterCorrect() {
        return preferences.getBoolean(KEY_CORRECT_AUTO_ADVANCE, false);
    }

    public void setAutoAdvanceAfterCorrect(boolean enabled) {
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_CORRECT_AUTO_ADVANCE, enabled);
        if (enabled && getAutoAdvanceDelaySeconds() == GeneralSettingsRules.AUTO_ADVANCE_DISABLED) {
            editor.putInt(KEY_AUTO_ADVANCE_DELAY, 2);
        }
        editor.apply();
    }

    public boolean shouldAutoAdvanceAfterWrong() {
        return preferences.getBoolean(KEY_WRONG_AUTO_ADVANCE, false);
    }

    public void setAutoAdvanceAfterWrong(boolean enabled) {
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_WRONG_AUTO_ADVANCE, enabled);
        if (enabled && getAutoAdvanceDelaySeconds() == GeneralSettingsRules.AUTO_ADVANCE_DISABLED) {
            editor.putInt(KEY_AUTO_ADVANCE_DELAY, 2);
        }
        editor.apply();
    }

    public int getAutoAdvanceDelaySeconds() {
        return GeneralSettingsRules.normalizeAutoAdvanceDelaySeconds(
                preferences.getInt(KEY_AUTO_ADVANCE_DELAY, 2)
        );
    }

    public void setAutoAdvanceDelaySeconds(int value) {
        int normalized = GeneralSettingsRules.normalizeAutoAdvanceDelaySeconds(value);
        SharedPreferences.Editor editor = preferences.edit().putInt(KEY_AUTO_ADVANCE_DELAY, normalized);
        if (normalized == GeneralSettingsRules.AUTO_ADVANCE_DISABLED) {
            editor.putBoolean(KEY_CORRECT_AUTO_ADVANCE, false)
                    .putBoolean(KEY_WRONG_AUTO_ADVANCE, false);
        }
        editor.apply();
    }

    public boolean shouldShowSourceExplanation() {
        return preferences.getBoolean(KEY_SHOW_SOURCE_EXPLANATION, true);
    }

    public void setShowSourceExplanation(boolean enabled) {
        preferences.edit().putBoolean(KEY_SHOW_SOURCE_EXPLANATION, enabled).apply();
    }

    public int getMockQuestionLimit() {
        return GeneralSettingsRules.normalizeMockQuestionLimit(
                preferences.getInt(KEY_MOCK_QUESTION_LIMIT, GeneralSettingsRules.MOCK_ALL_QUESTIONS)
        );
    }

    public void setMockQuestionLimit(int value) {
        preferences.edit().putInt(
                KEY_MOCK_QUESTION_LIMIT,
                GeneralSettingsRules.normalizeMockQuestionLimit(value)
        ).apply();
    }

    public boolean shouldShuffleMockQuestions() {
        return preferences.getBoolean(KEY_MOCK_SHUFFLE_QUESTIONS, true);
    }

    public void setShuffleMockQuestions(boolean enabled) {
        preferences.edit().putBoolean(KEY_MOCK_SHUFFLE_QUESTIONS, enabled).apply();
    }

    public boolean shouldShuffleMockOptions() {
        return preferences.getBoolean(KEY_MOCK_SHUFFLE_OPTIONS, true);
    }

    public void setShuffleMockOptions(boolean enabled) {
        preferences.edit().putBoolean(KEY_MOCK_SHUFFLE_OPTIONS, enabled).apply();
    }

    public boolean shouldWarnBeforeSubmittingUnansweredMock() {
        return preferences.getBoolean(KEY_MOCK_WARN_UNANSWERED, true);
    }

    public void setWarnBeforeSubmittingUnansweredMock(boolean enabled) {
        preferences.edit().putBoolean(KEY_MOCK_WARN_UNANSWERED, enabled).apply();
    }

    public int getQuestionFontMode() {
        return GeneralSettingsRules.normalizeQuestionFontMode(
                preferences.getInt(KEY_QUESTION_FONT_MODE, GeneralSettingsRules.QUESTION_FONT_STANDARD)
        );
    }

    public void setQuestionFontMode(int value) {
        preferences.edit().putInt(
                KEY_QUESTION_FONT_MODE,
                GeneralSettingsRules.normalizeQuestionFontMode(value)
        ).apply();
    }

    public int getQuizDensity() {
        return GeneralSettingsRules.normalizeQuizDensity(
                preferences.getInt(KEY_QUIZ_DENSITY, GeneralSettingsRules.QUIZ_DENSITY_STANDARD)
        );
    }

    public void setQuizDensity(int value) {
        preferences.edit().putInt(
                KEY_QUIZ_DENSITY,
                GeneralSettingsRules.normalizeQuizDensity(value)
        ).apply();
    }

    public boolean shouldOpenImagesFullscreen() {
        return preferences.getBoolean(KEY_IMAGE_FULLSCREEN, true);
    }

    public void setOpenImagesFullscreen(boolean enabled) {
        preferences.edit().putBoolean(KEY_IMAGE_FULLSCREEN, enabled).apply();
    }

    public boolean shouldUseHapticFeedback() {
        return preferences.getBoolean(KEY_HAPTIC_FEEDBACK, true);
    }

    public void setUseHapticFeedback(boolean enabled) {
        preferences.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply();
    }

    public boolean shouldKeepScreenOnDuringMock() {
        return preferences.getBoolean(KEY_MOCK_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOnDuringMock(boolean enabled) {
        preferences.edit().putBoolean(KEY_MOCK_KEEP_SCREEN_ON, enabled).apply();
    }

    public boolean shouldDiscoverManualZip() {
        return preferences.getBoolean(KEY_DISCOVER_MANUAL_ZIP, false);
    }

    public void setDiscoverManualZip(boolean enabled) {
        preferences.edit().putBoolean(KEY_DISCOVER_MANUAL_ZIP, enabled).apply();
    }

    public boolean hasCustomDownloadDirectory() {
        return !preferences.getString(KEY_DOWNLOAD_TREE_URI, "").isEmpty();
    }

    public Uri getDownloadTreeUri() {
        String raw = preferences.getString(KEY_DOWNLOAD_TREE_URI, "");
        return raw.isEmpty() ? null : Uri.parse(raw);
    }

    public String getDownloadDirectoryLabel() {
        return preferences.getString(KEY_DOWNLOAD_TREE_LABEL, DownloadArchiveStore.DISPLAY_FOLDER);
    }

    public void setDownloadTree(Uri treeUri, String label) {
        preferences.edit()
                .putString(KEY_DOWNLOAD_TREE_URI, treeUri == null ? "" : treeUri.toString())
                .putString(KEY_DOWNLOAD_TREE_LABEL,
                        label == null || label.trim().isEmpty() ? "自定义文件夹" : label.trim())
                .apply();
    }

    public void clearDownloadTree() {
        preferences.edit()
                .remove(KEY_DOWNLOAD_TREE_URI)
                .remove(KEY_DOWNLOAD_TREE_LABEL)
                .apply();
    }
}
