package com.example.aiquizlite;

import org.junit.Test;

import static com.example.aiquizlite.AiModelIcons.Brand.*;
import static org.junit.Assert.assertEquals;

public class AiModelIconsTest {
    @Test public void resolvesAllVendorsAndAliasesIgnoringCase() {
        assertEquals(DEEPSEEK, AiModelIcons.match("vendor/DeepSeek-Reasoner"));
        assertEquals(QWEN, AiModelIcons.match("QWEN3-VL"));
        assertEquals(OPENAI, AiModelIcons.match("gpt-4o-mini"));
        assertEquals(GEMINI, AiModelIcons.match("Gemini-Flash"));
        assertEquals(ZHIPU, AiModelIcons.match("GLM-4"));
        assertEquals(ZHIPU, AiModelIcons.match("CHATGLM"));
        assertEquals(KIMI, AiModelIcons.match("MOONSHOT-V1"));
        assertEquals(KIMI, AiModelIcons.match("kimi-k2"));
        assertEquals(DOUBAO, AiModelIcons.match("Doubao-Pro"));
        assertEquals(STEPFUN, AiModelIcons.match("step-3"));
        assertEquals(STEPFUN, AiModelIcons.match("StepFun-V"));
    }

    @Test public void prefersLongestKeywordThenVendorPriority() {
        assertEquals(ZHIPU, AiModelIcons.match("gemini-chatglm"));
        assertEquals(QWEN, AiModelIcons.match("gpt-qwen"));
        assertEquals(OPENAI, AiModelIcons.match("gpt-glm"));
        assertEquals(DEEPSEEK, AiModelIcons.match("moonshot-deepseek"));
        assertEquals(DEFAULT, AiModelIcons.match("本地-custom-model"));
        assertEquals(DEFAULT, AiModelIcons.match(null));
    }
}
