package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MockOptionOrderRulesTest {
    @Test
    public void shufflesOrdinaryChoiceButKeepsJudgeAndLetterReferencesStable() {
        Question ordinary = question("single", "哪个协议工作在网络层？", "A");
        Question judge = question("judge", "操作系统只管理硬件资源。", "B");
        Question letterReference = question("multi", "A和B两项都正确的是？", "A", "B");
        Question embeddedLetterReference = question("single", "方案A所描述的是？", "A");

        assertTrue(MockOptionOrderRules.canShuffle(ordinary));
        assertFalse(MockOptionOrderRules.canShuffle(judge));
        assertFalse(MockOptionOrderRules.canShuffle(letterReference));
        assertFalse(MockOptionOrderRules.canShuffle(embeddedLetterReference));
        assertEquals(
                Arrays.asList("A", "B", "C", "D"),
                MockOptionOrderRules.createOptionOrder(judge, new ZeroRandom())
        );
    }

    @Test
    public void displayedLabelsStayAbcdWhileTagsKeepOriginalAnswerKeys() {
        Question question = question("single", "普通单选题", "A");
        SessionQuestion session = new SessionQuestion(
                question,
                Arrays.asList("D", "B", "A", "C"),
                true
        );

        assertEquals("A. 内容丁", session.displayText(session.getDisplayOptions().get(0)));
        assertEquals("D", session.getDisplayOptions().get(0).getKey());
        assertEquals("C", session.getDisplayLabelForOriginalKey("A"));
    }

    private Question question(String type, String stem, String... answers) {
        List<Question.Option> options = Arrays.asList(
                new Question.Option("A", "内容甲"),
                new Question.Option("B", "内容乙"),
                new Question.Option("C", "内容丙"),
                new Question.Option("D", "内容丁")
        );
        return new Question(
                stem,
                1,
                "测试卷",
                1,
                type,
                stem,
                options,
                Arrays.asList(answers),
                null,
                null
        );
    }

    private static final class ZeroRandom extends Random {
        @Override
        public int nextInt(int bound) {
            return 0;
        }
    }
}
