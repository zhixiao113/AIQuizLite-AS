package com.example.aiquizlite;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class MaterialImportSpeedTest {
    @Test
    public void failedWavePreservesProviderErrorAndDoesNotStartLaterBatches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OpenAiCompatibleClient.AiClientException expected = new OpenAiCompatibleClient.AiClientException(
                OpenAiCompatibleClient.FailureKind.RATE_LIMIT, "请求受限");
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createLongChatCompletion(ApiConfig config, String system, String prompt)
                    throws AiClientException {
                calls.incrementAndGet();
                throw expected;
            }
        };
        List<MaterialTextRules.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chapters.add(new MaterialTextRules.Chapter("chapter" + i, "文".repeat(20000)));
        }
        try {
            new MaterialQuestionGenerator(client).convertQuestionBank(null, "test", chapters, null);
            fail("Provider failure must not be treated as a complete question bank");
        } catch (OpenAiCompatibleClient.AiClientException actual) {
            assertSame(expected, actual);
        }
        assertTrue(calls.get() <= 2);
    }

    @Test
    public void classificationUsesSmallSampleIncludingLastChapter() throws Exception {
        List<MaterialTextRules.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            chapters.add(new MaterialTextRules.Chapter("chapter" + i, "body".repeat(400)));
        }
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createLongChatCompletion(ApiConfig config, String system, String prompt) {
                assertTrue("Classification prompt should stay small", prompt.length() < 7000);
                assertTrue("Sample should include document end", prompt.contains("chapter59"));
                assertFalse("Classification need not estimate question counts", prompt.contains("typeCounts"));
                return "{\"documentKind\":\"question_bank\",\"confidence\":0.9,\"reason\":\"题目\"}";
            }
        };
        assertTrue(new MaterialQuestionGenerator(client).analyzeDocument(null, "test.txt", chapters).isQuestionBank());
    }

    @Test
    public void twoBatchesOverlapButResultsKeepSourceOrder() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient() {
            @Override String createLongChatCompletion(ApiConfig config, String system, String prompt) {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    bothStarted.countDown();
                    assertTrue("Both requests should run together", bothStarted.await(2, TimeUnit.SECONDS));
                    String chapter = prompt.contains("FIRST_MARKER") ? "第一章" : "第二章";
                    return new JSONArray().put(new JSONObject()
                            .put("type", "fill_blank").put("stem", chapter + "题目")
                            .put("options", new JSONArray()).put("answers", new JSONArray().put("答案"))
                            .put("sourceChapter", chapter)).toString();
                } catch (Exception e) {
                    throw new AssertionError(e);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        List<Integer> progress = new ArrayList<>();
        MaterialQuestionGenerator.QuestionBankConversionResult result =
                new MaterialQuestionGenerator(client).convertQuestionBank(null, "test", Arrays.asList(
                        new MaterialTextRules.Chapter("第一章", "FIRST_MARKER" + "文".repeat(20000)),
                        new MaterialTextRules.Chapter("第二章", "SECOND_MARKER" + "文".repeat(20000))),
                        (completed, total, message) -> progress.add(completed));
        assertEquals(2, peak.get());
        assertEquals(2, result.getAccepted().length());
        assertEquals("第一章", result.getAccepted().getJSONObject(0).getString("sourceChapter"));
        assertEquals("第二章", result.getAccepted().getJSONObject(1).getString("sourceChapter"));
        assertTrue(progress.contains(1));
    }
}
