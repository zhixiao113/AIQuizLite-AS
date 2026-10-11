package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PracticeNavigationRulesTest {
    @Test
    public void answeringLastQuestionFirstReturnsToFirstUnanswered() {
        assertEquals(0, PracticeNavigationRules.nextIndex(99, 100, index -> index == 99));
    }

    @Test
    public void skippedQuestionIsNotCountedAsCompleted() {
        assertEquals(7, PracticeNavigationRules.nextIndex(19, 20, index -> index != 7));
    }

    @Test
    public void completesOnlyAfterEveryQuestionHasAnAnswer() {
        assertEquals(-1, PracticeNavigationRules.nextIndex(19, 20, index -> true));
    }

    @Test
    public void normalNextStillVisitsAdjacentQuestionIncludingAnsweredOnes() {
        assertEquals(6, PracticeNavigationRules.nextIndex(5, 20, index -> true));
    }

    @Test
    public void oneQuestionAndEmptySessionHaveNoNextQuestion() {
        assertEquals(-1, PracticeNavigationRules.nextIndex(0, 1, index -> true));
        assertEquals(-1, PracticeNavigationRules.nextIndex(0, 0, index -> false));
    }
}
