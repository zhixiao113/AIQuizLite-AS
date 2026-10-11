package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class CompactQuestionBankTest {
    @Test
    public void classificationAndConversionUseExtractionRequests() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt) {
                if (calls.getAndIncrement() == 0) {
                    return "{\"documentKind\":\"question_bank\",\"confidence\":1,\"reason\":\"题目\"}";
                }
                return "[{\"t\":\"fill_blank\",\"s\":\"题目\",\"a\":[\"答案\"],\"c\":0}]";
            }
            @Override String createLongChatCompletion(ApiConfig config, String system, String prompt) {
                throw new AssertionError("Extraction must not use the generation request path");
            }
        };
        MaterialQuestionGenerator generator = new MaterialQuestionGenerator(client);
        java.util.List<MaterialTextRules.Chapter> chapters = Arrays.asList(
                new MaterialTextRules.Chapter("章节", "题目 答案"));
        assertTrue(generator.analyzeDocument(null, "test", chapters).isQuestionBank());
        assertEquals(1, generator.convertQuestionBank(null, "test", chapters, null).getAccepted().length());
        assertEquals(2, calls.get());
    }

    @Test
    public void compactResponseKeepsAnswersExplanationsAndChapters() throws Exception {
        String compact = "[{\"t\":\"single\",\"s\":\"选择正确内容\",\"o\":[[\"A\",\"正确\"],[\"B\",\"错误\"]],\"a\":[\"A\"],\"e\":\"原有解析\",\"c\":0},"
                + "{\"t\":\"fill_blank\",\"s\":\"填写两个答案\",\"a\":[\"甲\",\"乙\"],\"c\":1}]";
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createLongChatCompletion(ApiConfig config, String system, String prompt) {
                return compact;
            }
        };
        JSONArray result = new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                Arrays.asList(new MaterialTextRules.Chapter("第一章", "选择正确内容"),
                        new MaterialTextRules.Chapter("第二章", "填写两个答案")), null).getAccepted();
        assertEquals(2, result.length());
        JSONObject first = result.getJSONObject(0);
        assertEquals("第一章", first.getString("sourceChapter"));
        assertEquals("原有解析", first.getString("explanation"));
        assertEquals("正确", first.getJSONArray("options").getJSONObject(0).getString("text"));
        assertEquals("A", first.getJSONArray("answers").getString(0));
        assertEquals("第二章", result.getJSONObject(1).getString("sourceChapter"));
        assertEquals(2, result.getJSONObject(1).getJSONArray("answers").length());
        assertTrue(result.getJSONObject(1).isNull("explanation"));
        assertTrue("Fixture output shrinks even before removing repeated snippets",
                compact.length() < result.toString().length());
    }

    @Test
    public void unresolvedOriginalQuestionIsRetainedForManualReview() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt) {
                return "[{\"t\":\"single\",\"s\":\"题目一\",\"o\":[[\"A\",\"对\"],[\"B\",\"错\"]],"
                        + "\"a\":[\"A\"],\"c\":0,\"n\":1},"
                        + "{\"u\":true,\"n\":2,\"reason\":\"缺少必要题图\"}]";
            }
        };
        MaterialQuestionGenerator.QuestionBankConversionResult result =
                new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                        Arrays.asList(new MaterialTextRules.Chapter("试卷", "1.（单选题）题目一\n2.（连线题）题目二")), null);
        assertEquals(1, result.getAccepted().length());
        assertEquals(1, result.getUnresolved().length());
        assertEquals(2, result.getUnresolved().getJSONObject(0).getInt("numberInPaper"));
    }

    @Test
    public void explicitProviderTruncationIsNotAcceptedAsSuccess() throws Exception {
        try {
            OpenAiCompatibleClient.parseContent("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"[]\"}}]}");
            fail("Truncated output must not be accepted even if JSON closes");
        } catch (OpenAiCompatibleClient.AiClientException error) {
            assertEquals(OpenAiCompatibleClient.FailureKind.RESPONSE, error.getKind());
            assertTrue(error.getMessage().contains("截断"));
        }
    }
}
