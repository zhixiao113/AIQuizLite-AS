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

    @Test
    public void cachePersistsActualModelAndLegacyHasUnknownSource() {
        AiExplanation legacy = AiExplanation.fromCachedJson("{\"reasoning\":\"旧讲解\"}");
        assertEquals("", legacy.getProfileId());
        assertEquals("", legacy.getModelId());
        AiExplanation sourced = legacy.withSource("profile-a", "qwen-plus");
        AiExplanation reopened = AiExplanation.fromCachedJson(sourced.toJson());
        assertEquals("profile-a", reopened.getProfileId());
        assertEquals("qwen-plus", reopened.getModelId());
        assertEquals("旧讲解", reopened.getReasoning());
    }

    @Test
    public void modelResponseCannotChooseItsOwnSource() {
        AiExplanation generated = AiExplanation.fromModelText(
                "{\"reasoning\":\"解释\",\"source_profile\":\"fake\",\"source_model\":\"fake\"}");
        assertEquals("", generated.getProfileId());
        assertEquals("", generated.getModelId());
    }
}
