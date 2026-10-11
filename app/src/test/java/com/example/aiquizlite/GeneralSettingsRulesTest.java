package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GeneralSettingsRulesTest {
    @Test
    public void invalidValuesFallBackToSafeDefaults() {
        assertEquals(GeneralSettingsRules.WRONG_REMOVAL_MANUAL,
                GeneralSettingsRules.normalizeWrongRemovalThreshold(8));
        assertEquals(2, GeneralSettingsRules.normalizeAutoAdvanceDelaySeconds(9));
        assertEquals(GeneralSettingsRules.MOCK_ALL_QUESTIONS,
                GeneralSettingsRules.normalizeMockQuestionLimit(35));
        assertEquals(GeneralSettingsRules.QUESTION_FONT_STANDARD,
                GeneralSettingsRules.normalizeQuestionFontMode(8));
        assertEquals(GeneralSettingsRules.QUIZ_DENSITY_STANDARD,
                GeneralSettingsRules.normalizeQuizDensity(-1));
    }

    @Test
    public void supportedValuesArePreserved() {
        assertEquals(3, GeneralSettingsRules.normalizeWrongRemovalThreshold(3));
        assertEquals(1, GeneralSettingsRules.normalizeAutoAdvanceDelaySeconds(1));
        assertEquals(GeneralSettingsRules.AUTO_ADVANCE_DISABLED,
                GeneralSettingsRules.normalizeAutoAdvanceDelaySeconds(0));
        assertEquals("不启用", GeneralSettingsRules.autoAdvanceDelayLabel(0));
        assertEquals("2 秒", GeneralSettingsRules.autoAdvanceDelayLabel(2));
        assertEquals(100, GeneralSettingsRules.normalizeMockQuestionLimit(100));
        assertEquals(GeneralSettingsRules.QUESTION_FONT_LARGE,
                GeneralSettingsRules.normalizeQuestionFontMode(GeneralSettingsRules.QUESTION_FONT_LARGE));
        assertEquals(GeneralSettingsRules.QUESTION_FONT_SMALL,
                GeneralSettingsRules.normalizeQuestionFontMode(GeneralSettingsRules.QUESTION_FONT_SMALL));
        assertEquals(GeneralSettingsRules.QUIZ_DENSITY_COMPACT,
                GeneralSettingsRules.normalizeQuizDensity(GeneralSettingsRules.QUIZ_DENSITY_COMPACT));
        assertEquals("较大", GeneralSettingsRules.questionFontLabel(GeneralSettingsRules.QUESTION_FONT_LARGE));
        assertEquals("较小", GeneralSettingsRules.questionFontLabel(GeneralSettingsRules.QUESTION_FONT_SMALL));
        assertEquals(16, GeneralSettingsRules.questionStemSizeSp(GeneralSettingsRules.QUESTION_FONT_SMALL));
        assertEquals(14, GeneralSettingsRules.questionOptionSizeSp(GeneralSettingsRules.QUESTION_FONT_SMALL));
        assertEquals(12, GeneralSettingsRules.matchingItemSizeSp(GeneralSettingsRules.QUESTION_FONT_SMALL));
        assertEquals(13, GeneralSettingsRules.matchingItemSizeSp(GeneralSettingsRules.QUESTION_FONT_STANDARD));
        assertEquals(15, GeneralSettingsRules.matchingItemSizeSp(GeneralSettingsRules.QUESTION_FONT_LARGE));
        assertEquals(18, GeneralSettingsRules.questionStemSizeSp(GeneralSettingsRules.QUESTION_FONT_STANDARD));
        assertEquals(21, GeneralSettingsRules.questionStemSizeSp(GeneralSettingsRules.QUESTION_FONT_LARGE));
        assertEquals("紧凑", GeneralSettingsRules.quizDensityLabel(GeneralSettingsRules.QUIZ_DENSITY_COMPACT));
    }
}
