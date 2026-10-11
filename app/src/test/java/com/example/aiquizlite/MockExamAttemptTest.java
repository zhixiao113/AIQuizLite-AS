package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MockExamAttemptTest {
    @Test
    public void jsonRoundTripLocksOrderSelectionsAndOriginalScore() {
        Question first = question("q1", "single", "A");
        Question second = question("q2", "single", "B");
        MockExamAttempt attempt = MockExamAttempt.create(
                "bank",
                Arrays.asList(first, second),
                new Random(7),
                100L
        );
        MockExamAttempt.Item firstItem = attempt.getItems().get(0);
        attempt.setSelection(firstItem.getQuestionId(), firstItem.getCorrectAnswers());
        attempt.setCurrentIndex(1);
        attempt.markCompleted(200L);

        MockExamAttempt restored = MockExamAttempt.fromJson(attempt.toJson());

        assertNotNull(restored);
        assertEquals(attempt.getId(), restored.getId());
        assertEquals(attempt.getItems().get(0).getQuestionId(), restored.getItems().get(0).getQuestionId());
        assertEquals(attempt.getItems().get(0).getOptionKeyOrder(), restored.getItems().get(0).getOptionKeyOrder());
        assertEquals(1, restored.getCorrectCount());
        assertEquals(1, restored.getUnansweredCount());
        assertEquals(50, restored.getScore());
        assertEquals(1, restored.getCurrentIndex());
        assertTrue(restored.isCompleted());

        restored.markCorrected(firstItem.getQuestionId());
        assertTrue(restored.findItem(firstItem.getQuestionId()).isCorrected());
        assertEquals(50, restored.getScore());
    }

    @Test
    public void wrongRetryContainsAnsweredWrongOnly() {
        MockExamAttempt attempt = MockExamAttempt.create(
                "bank",
                Arrays.asList(question("q1", "single", "A"), question("q2", "single", "B")),
                new Random(2),
                100L
        );
        MockExamAttempt.Item answered = attempt.getItems().get(0);
        String wrong = answered.getCorrectAnswers().contains("A") ? "B" : "A";
        attempt.setSelection(answered.getQuestionId(), Collections.singletonList(wrong));

        assertEquals(Collections.singletonList(answered.getQuestionId()), attempt.getWrongQuestionIds());
        assertEquals(1, attempt.getWrongCount());
        assertEquals(1, attempt.getUnansweredCount());
        assertFalse(answered.isCorrect());
    }

    @Test
    public void settingsCanKeepSourceOrderAndDisableOptionShuffle() {
        Question first = question("q1", "single", "A");
        Question second = question("q2", "single", "B");
        MockExamAttempt attempt = MockExamAttempt.create(
                "bank",
                Arrays.asList(first, second),
                new Random(9),
                100L,
                20,
                false,
                false
        );

        assertEquals("q1", attempt.getItems().get(0).getQuestionId());
        assertEquals(Arrays.asList("A", "B", "C"),
                attempt.getItems().get(0).getOptionKeyOrder());
    }

    @Test
    public void matchingSelectionStaysUnansweredUntilEveryRightItemIsConnected() {
        MatchingData matching = new MatchingData(MatchingData.LEFT_REUSABLE,
                Arrays.asList(new MatchingData.Item("L1", "左一"),
                        new MatchingData.Item("L2", "左二")),
                Arrays.asList(new MatchingData.Item("R1", "右一"),
                        new MatchingData.Item("R2", "右二")),
                Arrays.asList(new MatchingData.Pair("L1", "R1"),
                        new MatchingData.Pair("L1", "R2")));
        Question question = new Question("match", 1, "测试卷", 1, "matching", "连线",
                Collections.emptyList(), Collections.emptyList(), null, null,
                matching, Collections.emptyList());
        MockExamAttempt attempt = MockExamAttempt.create("bank",
                Collections.singletonList(question), new Random(1), 100L);
        attempt.setMatchingSelection("match", Collections.singletonList(
                new MatchingData.Pair("L1", "R1")));
        assertEquals(1, attempt.getUnansweredCount());
        attempt.setMatchingSelection("match", Arrays.asList(
                new MatchingData.Pair("L1", "R1"), new MatchingData.Pair("L2", "R2")));
        assertEquals(1, attempt.getWrongCount());
        assertEquals(0, attempt.getUnansweredCount());
        MockExamAttempt restored = MockExamAttempt.fromJson(attempt.toJson());
        assertEquals(attempt.getItems().get(0).getSelectedPairs(),
                restored.getItems().get(0).getSelectedPairs());
        assertEquals(0, restored.getScore());
    }

    private Question question(String id, String type, String answer) {
        return new Question(
                id,
                1,
                "测试卷",
                1,
                type,
                id,
                Arrays.asList(
                        new Question.Option("A", "选项A"),
                        new Question.Option("B", "选项B"),
                        new Question.Option("C", "选项C")
                ),
                Collections.singletonList(answer),
                null,
                null
        );
    }
}
