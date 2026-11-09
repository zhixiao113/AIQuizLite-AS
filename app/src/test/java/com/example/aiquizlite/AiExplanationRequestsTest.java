package com.example.aiquizlite;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AiExplanationRequestsTest {
    private final AiExplanation result = new AiExplanation("yes", "reason", "", "");
    private AiExplanationRouter.Result generated() {
        return new AiExplanationRouter.Result(result, null, java.util.Collections.emptyList(), false);
    }
    private static final class Cache implements AiExplanationRequests.Cache {
        final ConcurrentHashMap<String, String> disk = new ConcurrentHashMap<>();
        public AiExplanation get(String key) { return AiExplanation.fromCachedJson(disk.get(key)); }
        public void put(String key, AiExplanation value) { disk.put(key, value.toJson()); }
    }

    @Test public void persistedResultSurvivesNewCoordinatorAndForcedFailure() throws Exception {
        Cache cache = new Cache();
        AtomicInteger calls = new AtomicInteger();
        AiExplanationRequests.Generator generate = () -> { calls.incrementAndGet(); return generated(); };
        new AiExplanationRequests(Runnable::run).get("q", cache, false, generate);
        AiExplanationRequests reopened = new AiExplanationRequests(Runnable::run);
        assertEquals("yes", reopened.get("q", cache, false, generate).explanation.getConclusion());
        assertEquals(1, calls.get());
        try {
            reopened.get("q", cache, true, () -> { throw new OpenAiCompatibleClient.AiClientException("offline"); });
            fail();
        } catch (OpenAiCompatibleClient.AiClientException expected) { assertEquals("offline", expected.getMessage()); }
        AiExplanationRouter.Result unsuccessful = new AiExplanationRouter.Result(
                null, "no eligible model", java.util.Collections.emptyList(), false);
        assertSame(unsuccessful, reopened.get("q", cache, true, () -> unsuccessful));
        assertEquals("yes", cache.get("q").getConclusion());
        reopened.get("q", cache, true, generate);
        assertEquals(2, calls.get());
    }

    @Test public void concurrentReopenSharesRequestAndOtherQuestionsDoNotBlock() throws Exception {
        ExecutorService network = Executors.newFixedThreadPool(2);
        ExecutorService callers = Executors.newFixedThreadPool(3);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        Cache cache = new Cache();
        AtomicInteger calls = new AtomicInteger();
        AiExplanationRequests requests = new AiExplanationRequests(network);
        AiExplanationRequests.Generator slow = () -> {
            calls.incrementAndGet(); started.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return generated();
        };
        try {
            Future<AiExplanationRouter.Result> first = callers.submit(() -> requests.get("q", cache, false, slow));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<AiExplanationRouter.Result> second = callers.submit(() -> requests.get("q", cache, false, slow));
            Future<AiExplanationRouter.Result> other = callers.submit(() -> requests.get("other", cache, false, this::generated));
            assertEquals("yes", other.get(2, TimeUnit.SECONDS).explanation.getConclusion());
            finish.countDown();
            assertEquals(first.get(2, TimeUnit.SECONDS).explanation.toJson(), second.get(2, TimeUnit.SECONDS).explanation.toJson());
            assertEquals(1, calls.get());
        } finally { finish.countDown(); callers.shutdownNow(); network.shutdownNow(); }
    }

    @Test public void closingCallerDoesNotCancelGeneration() throws Exception {
        ExecutorService network = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1), saved = new CountDownLatch(1);
        Cache cache = new Cache();
        AiExplanationRequests requests = new AiExplanationRequests(network);
        try {
            Future<?> waiter = callers.submit(() -> {
                try {
                    requests.get("q", new AiExplanationRequests.Cache() {
                        public AiExplanation get(String key) { return cache.get(key); }
                        public void put(String key, AiExplanation value) { cache.put(key, value); saved.countDown(); }
                    }, false, () -> {
                        started.countDown();
                        try { finish.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { throw new AssertionError("generation cancelled", e); }
                        return generated();
                    });
                } catch (OpenAiCompatibleClient.AiClientException ignored) { }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            waiter.cancel(true);
            finish.countDown();
            assertTrue(saved.await(2, TimeUnit.SECONDS));
            assertEquals("yes", cache.get("q").getConclusion());
        } finally { finish.countDown(); callers.shutdownNow(); network.shutdownNow(); }
    }

    @Test public void modelSpecificCacheReopensAndFallbackUsesActualModelKey() throws Exception {
        Cache cache = new Cache();
        AiExplanation fallback = result.withSource("fallback", "qwen-plus");
        AiExplanationRequests requests = new AiExplanationRequests(Runnable::run);
        requests.getForModel("q", "preferred", cache, false,
                () -> new AiExplanationRouter.Result(fallback, null, java.util.Collections.emptyList(), false));
        assertNull(cache.get(AiExplanationCache.modelKeyFor("q", "preferred")));
        assertEquals("fallback", cache.get(AiExplanationCache.modelKeyFor("q", "fallback")).getProfileId());
        AiExplanationRouter.Result reopened = new AiExplanationRequests(Runnable::run).getForModel(
                "q", "fallback", cache, false, () -> { throw new AssertionError("Saved model content must be reused"); });
        assertTrue(reopened.cached);
        assertEquals("qwen-plus", reopened.modelId);
    }

    @Test public void cachedModelReturnsBeforeSchedulingNetworkWork() throws Exception {
        Cache cache = new Cache();
        AiProfiles.Model model = new AiProfiles.Model("b", new ApiConfig("https://example.com", "fixture", "qwen-plus"),
                true, true, true, 0, 1);
        cache.put(AiExplanationCache.modelKeyFor("q", model.id), result.withSource(model.id, model.config.getModel()));
        AiExplanationRequests requests = new AiExplanationRequests(task -> {
            throw new AssertionError("A cached response must not wait for a network worker");
        });
        assertTrue(requests.getForModel("q", model, cache, false,
                () -> { throw new AssertionError("A cached response must not call the provider"); }).cached);
    }

    @Test public void regenerationUsesItsOwnWorkerAndRefreshesCachedContent() throws Exception {
        Cache cache = new Cache();
        AiProfiles.Model model = new AiProfiles.Model("b", new ApiConfig("https://example.com", "fixture", "qwen-plus"),
                true, true, true, 0, 1);
        String key = AiExplanationCache.modelKeyFor("q", model.id);
        cache.put(key, result.withSource(model.id, model.config.getModel()));
        AiExplanationRequests requests = new AiExplanationRequests(task -> {
            throw new AssertionError("Regeneration must run independently of the first-generation queue");
        }, Runnable::run);
        AiExplanation fresh = new AiExplanation("fresh", "reason", "", "").withSource(model.id, model.config.getModel());
        AiExplanationRouter.Result generated = requests.getForModel("q", model, cache, true, true,
                () -> new AiExplanationRouter.Result(fresh, null, java.util.Collections.emptyList(), false));
        assertFalse(generated.cached);
        assertEquals("fresh", cache.get(key).getConclusion());
    }

    @Test public void changedModelIdDoesNotReuseOldProfileCache() throws Exception {
        Cache cache = new Cache();
        String key = AiExplanationCache.modelKeyFor("q", "same-profile");
        cache.put(key, result.withSource("same-profile", "old-model"));
        AiProfiles.Model changed = new AiProfiles.Model("same-profile",
                new ApiConfig("https://example.com", "fixture", "new-model"), true, true, true, 0, 2);
        AiExplanation updated = result.withSource(changed.id, changed.config.getModel());
        AiExplanationRouter.Result actual = new AiExplanationRequests(Runnable::run).getForModel(
                "q", changed, cache, false,
                () -> new AiExplanationRouter.Result(updated, null, java.util.Collections.emptyList(), false));
        assertFalse(actual.cached);
        assertEquals("new-model", cache.get(key).getModelId());
    }

    @Test public void modelSelectionRequestsRunSeparatelyAndFailedRegenerationKeepsCache() throws Exception {
        ExecutorService network = Executors.newFixedThreadPool(2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1), finishFirst = new CountDownLatch(1);
        Cache cache = new Cache();
        AiExplanationRequests requests = new AiExplanationRequests(network);
        try {
            Future<AiExplanationRouter.Result> first = callers.submit(() -> requests.getForModel("q", "a", cache,
                    false, () -> {
                        firstStarted.countDown();
                        try { if (!finishFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                        return new AiExplanationRouter.Result(result.withSource("a", "model-a"), null,
                                java.util.Collections.emptyList(), false);
                    }));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Future<AiExplanationRouter.Result> second = callers.submit(() -> requests.getForModel("q", "b", cache,
                    true, () -> new AiExplanationRouter.Result(result.withSource("b", "model-b"), null,
                            java.util.Collections.emptyList(), false)));
            assertEquals("b", second.get(2, TimeUnit.SECONDS).profileId);
            finishFirst.countDown();
            assertEquals("a", first.get(2, TimeUnit.SECONDS).profileId);
            requests.getForModel("q", "b", cache, true,
                    () -> new AiExplanationRouter.Result(null, "offline", java.util.Collections.emptyList(), false));
            assertEquals("model-b", cache.get(AiExplanationCache.modelKeyFor("q", "b")).getModelId());
        } finally { finishFirst.countDown(); callers.shutdownNow(); network.shutdownNow(); }
    }

    @Test public void olderStandardCompletionCannotOverwriteNewerRefinement() throws Exception {
        ExecutorService network = Executors.newFixedThreadPool(2);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch standardStarted = new CountDownLatch(1), finishStandard = new CountDownLatch(1);
        Cache cache = new Cache();
        AiExplanationRequests requests = new AiExplanationRequests(network);
        try {
            Future<AiExplanationRouter.Result> standard = callers.submit(() -> requests.getForModel("q", "a", cache,
                    false, () -> {
                        standardStarted.countDown();
                        try { if (!finishStandard.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                        return new AiExplanationRouter.Result(result.withSource("a", "model-a"), null,
                                java.util.Collections.emptyList(), false);
                    }));
            assertTrue(standardStarted.await(2, TimeUnit.SECONDS));
            AiExplanation refined = new AiExplanation("simplified", "analogy", "", "").withSource("a", "model-a");
            requests.getForModel("q", "a", cache, true,
                    () -> new AiExplanationRouter.Result(refined, null, java.util.Collections.emptyList(), false));
            finishStandard.countDown();
            standard.get(2, TimeUnit.SECONDS);
            assertEquals("simplified", cache.get(AiExplanationCache.modelKeyFor("q", "a")).getConclusion());
        } finally { finishStandard.countDown(); callers.shutdownNow(); network.shutdownNow(); }
    }

    @Test public void explicitSelectionDoesNotJoinAnAutomaticFallbackRoute() throws Exception {
        ExecutorService network = Executors.newFixedThreadPool(2);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AiProfiles.Model model = new AiProfiles.Model("b", new ApiConfig("https://example.com", "fixture", "b"),
                true, true, true, 0, 1);
        Cache cache = new Cache();
        AiExplanationRequests requests = new AiExplanationRequests(network);
        try {
            Future<AiExplanationRouter.Result> automatic = callers.submit(() -> requests.getForModel("q", model,
                    cache, true, false, () -> {
                        started.countDown();
                        try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                        return new AiExplanationRouter.Result(result.withSource("c", "c"), null,
                                java.util.Collections.emptyList(), false);
                    }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            AiExplanationRouter.Result explicit = requests.getForModel("q", model, cache, true, true,
                    () -> new AiExplanationRouter.Result(null, "b failed", java.util.Collections.emptyList(), false));
            assertNull(explicit.explanation);
            assertEquals("b failed", explicit.error);
            finish.countDown();
            assertEquals("c", automatic.get(2, TimeUnit.SECONDS).profileId);
        } finally { finish.countDown(); callers.shutdownNow(); network.shutdownNow(); }
    }
}
