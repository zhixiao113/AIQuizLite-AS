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
}
