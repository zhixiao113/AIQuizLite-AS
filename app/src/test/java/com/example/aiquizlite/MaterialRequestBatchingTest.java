package com.example.aiquizlite;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class MaterialRequestBatchingTest {
    @Test
    public void mediumQuestionBankUsesOneAiConversionRequest() {
        assertEquals(
                1,
                MaterialQuestionGenerator.estimateQuestionBankRequests(
                        Collections.singletonList(chapterWithCharacters(24_000))
                )
        );
    }

    @Test
    public void mediumLearningDocumentUsesOneAiPlanningRequest() {
        assertEquals(
                1,
                MaterialQuestionGenerator.estimateLearningPlanRequests(
                        Collections.singletonList(chapterWithCharacters(24_000))
                )
        );
    }

    @Test
    public void mediumLearningDocumentUsesOneInitialGenerationRequest() {
        MaterialGenerationPlan plan = new MaterialGenerationPlan(
                "测试题库",
                "资料.txt",
                Collections.singletonList(new MaterialGenerationPlan.ChapterPlan(
                        "全文",
                        repeat('知', 24_000),
                        20
                )),
                MaterialGenerationPlan.recommendTypes(20)
        );

        assertEquals(1, MaterialQuestionGenerator.estimateInitialRequests(plan));
    }

    private static MaterialTextRules.Chapter chapterWithCharacters(int count) {
        return new MaterialTextRules.Chapter("全文", repeat('题', count));
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }
}
