package com.example.aiquizlite;

public final class GeneralSettingsRules {
    public static final int WRONG_REMOVAL_MANUAL = 0;
    public static final int MOCK_ALL_QUESTIONS = 0;
    public static final int AUTO_ADVANCE_DISABLED = 0;
    public static final int QUESTION_FONT_STANDARD = 0;
    public static final int QUESTION_FONT_LARGE = 1;
    public static final int QUESTION_FONT_SMALL = 2;
    public static final int QUIZ_DENSITY_STANDARD = 0;
    public static final int QUIZ_DENSITY_COMPACT = 1;

    private GeneralSettingsRules() {
    }

    public static int normalizeWrongRemovalThreshold(int value) {
        return value == 1 || value == 2 || value == 3 ? value : WRONG_REMOVAL_MANUAL;
    }

    public static int normalizeAutoAdvanceDelaySeconds(int value) {
        return value == AUTO_ADVANCE_DISABLED || value == 1 || value == 3 ? value : 2;
    }

    public static String autoAdvanceDelayLabel(int value) {
        int normalized = normalizeAutoAdvanceDelaySeconds(value);
        return normalized == AUTO_ADVANCE_DISABLED ? "不启用" : normalized + " 秒";
    }

    public static int normalizeMockQuestionLimit(int value) {
        return value == 20 || value == 50 || value == 100 ? value : MOCK_ALL_QUESTIONS;
    }

    public static int normalizeQuestionFontMode(int value) {
        return value == QUESTION_FONT_SMALL || value == QUESTION_FONT_LARGE
                ? value : QUESTION_FONT_STANDARD;
    }

    public static int normalizeQuizDensity(int value) {
        return value == QUIZ_DENSITY_COMPACT ? QUIZ_DENSITY_COMPACT : QUIZ_DENSITY_STANDARD;
    }

    public static String questionFontLabel(int value) {
        switch (normalizeQuestionFontMode(value)) {
            case QUESTION_FONT_SMALL: return "较小";
            case QUESTION_FONT_LARGE: return "较大";
            default: return "标准";
        }
    }

    public static int questionStemSizeSp(int value) {
        switch (normalizeQuestionFontMode(value)) {
            case QUESTION_FONT_SMALL: return 16;
            case QUESTION_FONT_LARGE: return 21;
            default: return 18;
        }
    }

    public static int questionOptionSizeSp(int value) {
        switch (normalizeQuestionFontMode(value)) {
            case QUESTION_FONT_SMALL: return 14;
            case QUESTION_FONT_LARGE: return 18;
            default: return 16;
        }
    }

    public static int matchingItemSizeSp(int value) {
        switch (normalizeQuestionFontMode(value)) {
            case QUESTION_FONT_SMALL: return 12;
            case QUESTION_FONT_LARGE: return 15;
            default: return 13;
        }
    }

    public static String quizDensityLabel(int value) {
        return normalizeQuizDensity(value) == QUIZ_DENSITY_COMPACT ? "紧凑" : "标准";
    }

    public static String wrongRemovalLabel(int value) {
        int normalized = normalizeWrongRemovalThreshold(value);
        return normalized == WRONG_REMOVAL_MANUAL ? "手动移除" : "连续答对 " + normalized + " 次";
    }

    public static String mockQuestionLimitLabel(int value) {
        int normalized = normalizeMockQuestionLimit(value);
        return normalized == MOCK_ALL_QUESTIONS ? "全部客观题" : normalized + " 题";
    }
}
