package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MaterialQuestionGeneratorBatchFlowTest {
    private static final ApiConfig CONFIG = new ApiConfig(
            "https://example.com/v1",
            "test-key",
            "test-model"
    );

    @Test
    public void plansMultipleSmallChaptersInOneAiRequest() throws Exception {
        FakeClient client = new FakeClient(
                "{\"chapters\":["
                        + "{\"title\":\"第一章\",\"typeCounts\":{\"single\":1}},"
                        + "{\"title\":\"第二章\",\"typeCounts\":{\"judge\":1}}]}"
        );
        MaterialQuestionGenerator generator = new MaterialQuestionGenerator(client);

        MaterialGenerationPlan plan = generator.recommendLearningPlan(
                CONFIG,
                "批量题库",
                "资料.txt",
                chapters(),
                null
        );

        assertEquals(1, client.calls);
        assertEquals(2, plan.getTargetCount());
        assertEquals(2, plan.getChapters().size());
        assertEquals(1, plan.allocateTypeMatrix().get("第一章").get(MaterialQuestionRules.SINGLE).intValue());
        assertEquals(1, plan.allocateTypeMatrix().get("第二章").get(MaterialQuestionRules.JUDGE).intValue());
    }

    @Test
    public void convertsWholeMediumQuestionBankThenValidatesLocally() throws Exception {
        FakeClient client = new FakeClient(new JSONArray()
                .put(single("第一章", "批量转换题"))
                .toString());
        MaterialQuestionGenerator generator = new MaterialQuestionGenerator(client);

        MaterialQuestionGenerator.QuestionBankConversionResult result =
                generator.convertQuestionBank(CONFIG, "现成题库", chapters(), null);

        assertEquals(1, client.calls);
        assertEquals(1, result.getAccepted().length());
        assertEquals("第一章", result.getAccepted().getJSONObject(0).getString("sourceChapter"));
    }

    @Test
    public void generatesWholeMediumLearningBankThenRunsOneFinalValidation() throws Exception {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put(MaterialQuestionRules.SINGLE, 2);
        MaterialGenerationPlan plan = new MaterialGenerationPlan(
                "批量题库",
                "资料.txt",
                Arrays.asList(
                        new MaterialGenerationPlan.ChapterPlan("第一章", "第一章知识", 1),
                        new MaterialGenerationPlan.ChapterPlan("第二章", "第二章知识", 1)
                ),
                types
        );
        JSONObject secondQuestion = single("第二章", "第二章题目");
        secondQuestion.remove("sourceChapter");
        FakeClient client = new FakeClient(new JSONArray()
                .put(single("第一章", "第一章题目"))
                .put(secondQuestion)
                .toString());

        MaterialQuestionGenerator.GenerationResult result =
                new MaterialQuestionGenerator(client).generate(
                        CONFIG,
                        plan,
                        new JSONArray(),
                        null
                );

        assertEquals(1, client.calls);
        assertTrue(result.getValidation().isComplete());
        assertEquals(2, result.getValidation().getAccepted().length());
    }

    private static List<MaterialTextRules.Chapter> chapters() {
        return Arrays.asList(
                new MaterialTextRules.Chapter("第一章", "第一章知识内容"),
                new MaterialTextRules.Chapter("第二章", "第二章知识内容")
        );
    }

    private static JSONObject single(String chapter, String stem) throws Exception {
        return new JSONObject()
                .put("type", "single")
                .put("stem", stem)
                .put("options", new JSONArray()
                        .put(new JSONObject().put("key", "A").put("text", "正确"))
                        .put(new JSONObject().put("key", "B").put("text", "错误")))
                .put("answers", new JSONArray().put("A"))
                .put("explanation", JSONObject.NULL)
                .put("sourceSnippet", chapter + "知识")
                .put("sourceChapter", chapter);
    }

    private static final class FakeClient extends OpenAiCompatibleClient {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        int calls;

        FakeClient(String... responses) {
            this.responses.addAll(Arrays.asList(responses));
        }

        @Override
        String createLongChatCompletion(
                ApiConfig config,
                String systemPrompt,
                String userPrompt
        ) {
            calls++;
            if (responses.isEmpty()) {
                throw new AssertionError("Unexpected extra AI request");
            }
            return responses.removeFirst();
        }
    }
}
