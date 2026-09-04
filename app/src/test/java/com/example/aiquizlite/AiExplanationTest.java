package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AiExplanationTest {
    @Test
    public void parsesStructuredJsonInsideCodeFence() {
        AiExplanation explanation = AiExplanation.fromModelText(
                "```json\n{\"conclusion\":\"结论\",\"reasoning\":\"原因\","
                        + "\"common_mistake\":\"易错\",\"memory_tip\":\"口诀\"}\n```"
        );

        assertEquals("结论", explanation.getConclusion());
        assertEquals("原因", explanation.getReasoning());
        assertEquals("易错", explanation.getCommonMistake());
        assertEquals("口诀", explanation.getMemoryTip());
        assertEquals("口诀", AiExplanation.fromCachedJson(explanation.toJson()).getMemoryTip());
    }

    @Test
    public void keepsReadablePlainTextFromCompatibleProvider() {
        AiExplanation explanation = AiExplanation.fromModelText("这是一段普通讲解");
        assertTrue(explanation.getReasoning().contains("普通讲解"));
    }
}
