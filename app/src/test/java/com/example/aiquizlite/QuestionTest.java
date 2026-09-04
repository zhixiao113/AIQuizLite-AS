package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class QuestionTest {
    @Test
    public void explicitFillBlankWithMultipleAnswersDoesNotBecomeMultiChoice() {
        Question question = new Question(
                "q1",
                1,
                "示例",
                1,
                "fill_blank",
                "目的安全区域为_____。",
                Collections.emptyList(),
                Arrays.asList("DMZ", "dmz"),
                null,
                " 示例解析 "
        );

        assertTrue(question.isFillBlank());
        assertEquals("fill_blank", question.getType());
        assertEquals("填空题", question.typeLabel());
        assertEquals("示例解析", question.getExplanation());
    }

    @Test
    public void acceptsChineseFillBlankAliasAndNormalizesEmptyExplanation() {
        Question question = new Question(
                "q2",
                2,
                "示例",
                2,
                "填空题",
                "接口必须加入_____。",
                Collections.emptyList(),
                Collections.singletonList("安全区域"),
                null,
                "   "
        );

        assertTrue(question.isFillBlank());
        assertNull(question.getExplanation());
    }
}
