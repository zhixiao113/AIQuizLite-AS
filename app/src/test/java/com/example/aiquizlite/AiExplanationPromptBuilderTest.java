package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AiExplanationPromptBuilderTest {
    private Question question() {
        return new Question(
                "q1",
                1,
                "防火墙",
                8,
                "single",
                "接口必须加入什么才能转发流量？",
                Arrays.asList(
                        new Question.Option("A", "安全区域"),
                        new Question.Option("B", "路由协议")
                ),
                Collections.singletonList("A"),
                null,
                "接口必须先加入安全区域。"
        );
    }

    @Test
    public void promptContainsQuestionAnswerResultAndOriginalExplanation() {
        String prompt = AiExplanationPromptBuilder.userPrompt(
                question(),
                Collections.singletonList("A"),
                true,
                "A. 安全区域",
                "A. 安全区域"
        );
        assertTrue(prompt.contains("接口必须加入什么"));
        assertTrue(prompt.contains("作答结果：正确"));
        assertTrue(prompt.contains("接口必须先加入安全区域"));
        assertTrue(AiExplanationPromptBuilder.systemPrompt().contains("只返回一个JSON对象"));
    }

    @Test
    public void cacheKeyChangesWithModelOrSelectedAnswer() {
        Question question = question();
        ApiConfig first = new ApiConfig("https://api.example.com/v1", "key", "model-a");
        ApiConfig second = new ApiConfig("https://api.example.com/v1", "key", "model-b");
        String keyA = AiExplanationCache.keyFor(
                "bank", question, Collections.singletonList("A"), true, first
        );
        String keyB = AiExplanationCache.keyFor(
                "bank", question, Collections.singletonList("A"), true, second
        );
        String keyWrong = AiExplanationCache.keyFor(
                "bank", question, Collections.singletonList("B"), false, first
        );
        assertNotEquals(keyA, keyB);
        assertNotEquals(keyA, keyWrong);
    }
}
