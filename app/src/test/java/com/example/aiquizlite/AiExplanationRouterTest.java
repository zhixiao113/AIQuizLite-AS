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
        assertEquals(ANSWER.getReasoning(), result.explanation.getReasoning());
        assertEquals("created-next", result.profileId);
        assertEquals("created-next", result.modelId);
        assertSame(pool.model("created-next"), result.model);
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
        assertEquals(ANSWER.getReasoning(), result.explanation.getReasoning());
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

    @Test public void explicitModelFailureDoesNotReplaceWithAnotherModel() throws Exception {
        Pool pool = new Pool().add("first", true, false, 0).add("selected", true, true, 0);
        List<String> attempts = new ArrayList<>();
        AiExplanationRouter.Result result = AiExplanationRouter.generate(pool, false, () -> NOW,
                () -> config -> {
                    attempts.add(config.getModel());
                    throw new OpenAiCompatibleClient.AiClientException("所选模型不可用");
                }, "selected", true, null);
        assertNull(result.explanation);
        assertEquals(java.util.Collections.singletonList("selected"), attempts);
        assertEquals("所选模型不可用", result.error);
        assertEquals(java.util.Collections.singletonList("selected"), pool.cooled);
    }

    @Test public void unavailableSelectionFallsBackToCurrentCreationOrder() throws Exception {
        Pool pool = new Pool().add("first", true, false, 0).add("selected", false, true, 0);
        List<String> progress = new ArrayList<>();
        AiExplanationRouter.Result result = AiExplanationRouter.generate(pool, false, () -> NOW,
                () -> config -> ANSWER, "selected", true, model -> progress.add(model.id));
        assertEquals("first", result.profileId);
        assertEquals(java.util.Collections.singletonList("first"), progress);
    }

    @Test public void cancellationDoesNotCoolOrFallBack() {
        Pool pool = new Pool().add("first", true, true, 0).add("next", true, true, 0);
        OpenAiCompatibleClient.AiClientException error = assertThrows(OpenAiCompatibleClient.AiClientException.class,
                () -> AiExplanationRouter.generate(pool, false, () -> NOW, () -> config -> {
                    throw new OpenAiCompatibleClient.AiClientException(
                            OpenAiCompatibleClient.FailureKind.CANCELLED, "已取消");
                }));
        assertTrue(error.isCancelled());
        assertTrue(pool.cooled.isEmpty());
    }

    @Test public void eligiblePoolHidesUnavailableAndTextOnlyForImageQuestions() {
        Pool pool = new Pool().add("disabled", false, true, 0).add("cooling", true, true, NOW + 1)
                .add("text", true, false, 0).add("vision", true, true, NOW);
        List<AiProfiles.Model> models = AiExplanationRouter.eligibleModels(pool, true, NOW);
        assertEquals(1, models.size());
        assertEquals("vision", models.get(0).id);
    }

    @Test public void timeoutFallsBackWithoutCoolingAndSelectedModelRemainsRetryable() throws Exception {
        Pool pool = new Pool().add("slow", true, true, 0).add("next", true, true, 0);
        AiExplanationRouter.Preparation preparation = () -> config -> {
            if ("slow".equals(config.getModel())) {
                throw new OpenAiCompatibleClient.AiClientException(
                        OpenAiCompatibleClient.FailureKind.TIMEOUT, "等待超时，请重试");
            }
            return ANSWER;
        };
        AiExplanationRouter.Result automatic = AiExplanationRouter.generate(pool, true, () -> NOW, preparation);
        assertEquals("next", automatic.profileId);
        assertFalse(automatic.failures.get(0).cooled);
        assertTrue(pool.cooled.isEmpty());
        AiExplanationRouter.Result pinned = AiExplanationRouter.generate(pool, true, () -> NOW,
                preparation, "slow", true, null);
        assertNull(pinned.explanation);
        assertEquals("等待超时，请重试", pinned.error);
        assertEquals(1, pinned.failures.size());
        assertFalse(pinned.failures.get(0).cooled);
        assertTrue(AiExplanationRouter.eligible(pool.model("slow"), true, NOW));
    }

    @Test public void networkFailureDoesNotCoolButCredentialFailureStillDoes() throws Exception {
        Pool pool = new Pool().add("network", true, false, 0).add("auth", true, false, 0);
        AiExplanationRouter.Result failed = AiExplanationRouter.generate(pool, false, () -> NOW, () -> config -> {
            OpenAiCompatibleClient.FailureKind kind = "network".equals(config.getModel())
                    ? OpenAiCompatibleClient.FailureKind.NETWORK : OpenAiCompatibleClient.FailureKind.AUTH;
            throw new OpenAiCompatibleClient.AiClientException(kind, "test failure");
        });
        assertNull(failed.explanation);
        assertEquals(java.util.Collections.singletonList("auth"), pool.cooled);
        assertFalse(failed.failures.get(0).cooled);
        assertTrue(failed.failures.get(1).cooled);
    }
}
