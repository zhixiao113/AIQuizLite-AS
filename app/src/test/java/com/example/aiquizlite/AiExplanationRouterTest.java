package com.example.aiquizlite;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AiExplanationRouterTest {
    private static final long NOW = 5_000;
    private static final AiExplanation ANSWER = new AiExplanation("完成", "解释", "", "");

    private static final class Pool implements AiExplanationRouter.Pool {
        final Map<String, AiProfiles.Model> saved = new LinkedHashMap<>();
        final List<String> cooled = new ArrayList<>();

        Pool add(String id, boolean enabled, boolean vision, long until) {
            saved.put(id, new AiProfiles.Model(id, new ApiConfig("https://example.com", "fixture-key", id),
                    enabled, vision, true, until, 1));
            return this;
        }
        @Override public List<AiProfiles.Model> models() { return new ArrayList<>(saved.values()); }
        @Override public AiProfiles.Model model(String id) { return saved.get(id); }
        @Override public boolean markFailed(AiProfiles.Model attempted, long now) {
            cooled.add(attempted.id);
            add(attempted.id, attempted.enabled, attempted.visionSupported, now + AiProfiles.COOLDOWN_MILLIS);
            return true;
        }
    }

    @Test public void textTriesCreationOrderSkipsInactiveAndCoolsOnlyFailedModel() throws Exception {
        Pool pool = new Pool().add("disabled", false, true, 0).add("cooling", true, true, NOW + 1)
                .add("created-first", true, false, 0).add("created-next", true, true, 0).add("last", true, true, 0);
        List<String> attempts = new ArrayList<>();
        AiExplanationRouter.Result result = AiExplanationRouter.generate(pool, false, () -> NOW, () -> config -> {
            attempts.add(config.getModel());
            if ("created-first".equals(config.getModel())) throw new OpenAiCompatibleClient.AiClientException("服务失败");
            return ANSWER;
        });
        assertSame(ANSWER, result.explanation);
        assertEquals(Arrays.asList("created-first", "created-next"), attempts);
        assertEquals(java.util.Collections.singletonList("created-first"), pool.cooled);
        assertEquals("created-first", result.failures.get(0).modelId);
        assertTrue(result.failures.get(0).cooled);
        assertEquals(NOW + AiProfiles.COOLDOWN_MILLIS, pool.model("created-first").cooldownUntil);
    }

    @Test public void imageUsesOnlyVisionModelsAndTriesNextAfterFailure() throws Exception {
        Pool pool = new Pool().add("text", true, false, 0).add("vision-first", true, true, NOW)
                .add("vision-next", true, true, 0);
        List<String> attempts = new ArrayList<>();
        AiExplanationRouter.Result result = AiExplanationRouter.generate(pool, true, () -> NOW, () -> config -> {
            attempts.add(config.getModel());
            if ("vision-first".equals(config.getModel())) throw new OpenAiCompatibleClient.AiClientException("图片请求失败");
            return ANSWER;
        });
        assertSame(ANSWER, result.explanation);
        assertEquals(Arrays.asList("vision-first", "vision-next"), attempts);
    }

    @Test public void noVisionReportsConfigurationWithoutReadingImages() throws Exception {
        Pool pool = new Pool().add("text", true, false, 0).add("vision", true, true, NOW + 1);
        AiExplanationRouter.Result result = AiExplanationRouter.generate(pool, true, () -> NOW, () -> {
            throw new AssertionError("No eligible image model, so don't read local files");
        });
        assertNull(result.explanation);
        assertTrue(result.error.startsWith("请配置识图模型"));
        assertTrue(result.failures.isEmpty());
    }

    @Test public void localImageFailureDoesNotCoolAnEligibleModel() {
        Pool pool = new Pool().add("vision", true, true, 0);
        OpenAiCompatibleClient.AiClientException error = assertThrows(OpenAiCompatibleClient.AiClientException.class,
                () -> AiExplanationRouter.generate(pool, true, () -> NOW, () -> {
                    throw new OpenAiCompatibleClient.AiClientException("题库图片缺失");
                }));
        assertEquals("题库图片缺失", error.getMessage());
        assertTrue(pool.cooled.isEmpty());
    }

    @Test public void allFailedReturnsEachFailureAndNoCacheableExplanation() throws Exception {
        Pool pool = new Pool().add("first", true, false, 0).add("next", true, true, 0);
        AiExplanationRouter.Result result = AiExplanationRouter.generate(pool, false, () -> NOW, () -> config -> {
            throw new OpenAiCompatibleClient.AiClientException("调用失败");
        });
        assertNull(result.explanation);
        assertEquals(2, result.failures.size());
        assertEquals(Arrays.asList("first", "next"), pool.cooled);
    }
}
