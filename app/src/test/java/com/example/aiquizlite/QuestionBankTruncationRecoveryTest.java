package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class QuestionBankTruncationRecoveryTest {
    private static String truncatedResponse() throws OpenAiCompatibleClient.AiClientException {
        return OpenAiCompatibleClient.parseContent(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"[{\"}}]}");
    }

    @Test
    public void twoHundredQuestionRecoveryKeepsOrderAndUpdatesProgress() throws Exception {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            source.append("原题_").append(i).append("_题干：请填写对应知识点。参考答案：答案\n");
        }
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt)
                    throws AiClientException {
                if (calls.incrementAndGet() == 1) {
                    return truncatedResponse();
                }
                String raw = prompt.substring(prompt.lastIndexOf("\n原文：") + 4);
                int start = raw.indexOf("【本轮题干范围开始】");
                int end = raw.indexOf("【本轮题干范围结束】");
                assertTrue(start >= 0 && end > start);
                JSONArray questions = new JSONArray();
                try {
                    for (int i = 0; i < 200; i++) {
                        String stem = "原题_" + i + "_题干";
                        int position = raw.indexOf(stem);
                        assertTrue("All source context remains available", position >= 0);
                        if (position > start && position < end) {
                            questions.put(new JSONObject().put("t", "fill_blank").put("s", stem)
                                    .put("a", new JSONArray().put("答案")).put("c", 0));
                        }
                    }
                    return questions.toString();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        };
        java.util.List<String> progress = new java.util.ArrayList<>();
        JSONArray result = new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                Arrays.asList(new MaterialTextRules.Chapter("章节", source.toString())),
                (done, total, message) -> progress.add(done + "/" + total)).getAccepted();
        assertEquals(3, calls.get());
        assertEquals(200, result.length());
        for (int i = 0; i < 200; i++) {
            assertEquals("原题_" + i + "_题干", result.getJSONObject(i).getString("stem"));
        }
        assertTrue(progress.contains("0/2"));
        assertTrue(progress.contains("1/2"));
        assertEquals("2/2", progress.get(progress.size() - 1));
    }

    @Test
    public void repeatedTruncationStopsAtTheRecoveryDepthLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt)
                    throws AiClientException {
                calls.incrementAndGet();
                return truncatedResponse();
            }
        };
        try {
            new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                    Arrays.asList(new MaterialTextRules.Chapter("章节", "资料\n".repeat(4000))), null);
            fail("Exhausted recovery must not silently accept an incomplete bank");
        } catch (OpenAiCompatibleClient.AiClientException error) {
            assertTrue(error.isOutputTruncated());
            assertEquals(3, calls.get());
            assertTrue(AiFailureInfo.from(error).getRecovery().contains("仍可能截断"));
        }
    }

    @Test
    public void nonTruncationFailuresDoNotTriggerExtraPaidRequests() throws Exception {
        for (OpenAiCompatibleClient.FailureKind kind : new OpenAiCompatibleClient.FailureKind[]{
                OpenAiCompatibleClient.FailureKind.AUTH, OpenAiCompatibleClient.FailureKind.NETWORK,
                OpenAiCompatibleClient.FailureKind.RATE_LIMIT, OpenAiCompatibleClient.FailureKind.RESPONSE}) {
            AtomicInteger calls = new AtomicInteger();
            OpenAiCompatibleClient.AiClientException expected =
                    new OpenAiCompatibleClient.AiClientException(kind, "错误");
            OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
                @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt)
                        throws AiClientException {
                    calls.incrementAndGet();
                    throw expected;
                }
            };
            try {
                new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                        Arrays.asList(new MaterialTextRules.Chapter("章节", "资料\n".repeat(4000))), null);
                fail("Expected failure");
            } catch (OpenAiCompatibleClient.AiClientException error) {
                assertSame(expected, error);
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    public void successfulPartialWindowIsNotReturnedWhenTheNextWindowFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleClient.AiClientException expected = new OpenAiCompatibleClient.AiClientException(
                OpenAiCompatibleClient.FailureKind.NETWORK, "网络中断");
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt)
                    throws AiClientException {
                int call = calls.incrementAndGet();
                if (call == 1) return truncatedResponse();
                if (call == 2) return "[{\"t\":\"fill_blank\",\"s\":\"成功的一题\",\"a\":[\"答案\"],\"c\":0}]";
                throw expected;
            }
        };
        try {
            new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                    Arrays.asList(new MaterialTextRules.Chapter("章节", "资料\n".repeat(4000))), null);
            fail("Incomplete recovery cannot become a successful import");
        } catch (OpenAiCompatibleClient.AiClientException error) {
            assertSame(expected, error);
            assertEquals(3, calls.get());
        }
    }

    @Test
    public void truncatedSingleBatchRecoversWithoutLosingTheAnswerContext() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createExtractionChatCompletion(ApiConfig config, String system, String prompt)
                    throws AiClientException {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return OpenAiCompatibleClient.parseContent(
                            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"[{\"}}]}");
                }
                assertTrue("Recovery retains the complete source, including answer key at end",
                        prompt.contains("FINAL_ANSWER_KEY"));
                assertTrue(prompt.contains("FIRST_QUESTION"));
                assertTrue(prompt.contains("【本轮题干范围开始】"));
                assertTrue(prompt.contains("【本轮题干范围结束】"));
                return "[{\"t\":\"fill_blank\",\"s\":\"第" + call
                        + "组原题\",\"a\":[\"答案\"],\"c\":0}]";
            }
        };
        JSONArray result = new MaterialQuestionGenerator(client).convertQuestionBank(null, "test",
                Arrays.asList(new MaterialTextRules.Chapter("章节",
                        "FIRST_QUESTION\n" + "资料行\n".repeat(1000) + "FINAL_ANSWER_KEY")), null).getAccepted();
        assertEquals(3, calls.get());
        assertEquals(2, result.length());
        assertEquals("第2组原题", result.getJSONObject(0).getString("stem"));
        assertEquals("第3组原题", result.getJSONObject(1).getString("stem"));
    }

    @Test
    public void officialExtractionSuppliesAnOutputBudgetForLargeBanks() throws Exception {
        JSONObject body = OpenAiCompatibleClient.requestBody(
                new ApiConfig("https://api.deepseek.com", "test", "deepseek-v4-flash"),
                "system", "原题".repeat(12000), true);
        assertTrue("Do not rely on the provider's default output allowance", body.optInt("max_tokens") >= 32768);
        assertTrue(body.getInt("max_tokens") <= 65536);
    }
}
