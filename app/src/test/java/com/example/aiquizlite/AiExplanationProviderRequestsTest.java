package com.example.aiquizlite;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AiExplanationProviderRequestsTest {
    @Test public void reopeningDuringFallbackSharesProviderAndCancelingWaiterKeepsGeneration() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AiExplanation> answer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String key = "fallback-reopening";
        Thread original = new Thread(() -> {
            try {
                answer.set(AiExplanationProviderRequests.get(key, () -> {
                    calls.incrementAndGet();
                    started.countDown();
                    try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    return new AiExplanation("shared", "reason", "", "");
                }));
            } catch (Throwable error) { failure.set(error); }
        });
        AtomicReference<OpenAiCompatibleClient.AiClientException> cancelled = new AtomicReference<>();
        Thread reopened = new Thread(() -> {
            try {
                AiExplanationProviderRequests.get(key, () -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Duplicate provider request");
                });
            } catch (OpenAiCompatibleClient.AiClientException error) { cancelled.set(error); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            original.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            reopened.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (reopened.isAlive() && reopened.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.WAITING, reopened.getState());
            reopened.interrupt();
            reopened.join(2000);
            assertNotNull(cancelled.get());
            assertTrue(cancelled.get().isCancelled());
            finish.countDown();
            original.join(2000);
            assertFalse(original.isAlive());
            assertNull(failure.get());
            assertEquals(1, calls.get());
            assertEquals("shared", answer.get().getConclusion());
        } finally {
            finish.countDown();
            reopened.interrupt();
            original.join(2000);
            reopened.join(2000);
        }
    }

    @Test public void providerIdentityIncludesConfigPromptAndImages() {
        ApiConfig config = new ApiConfig("https://example.com", "fixture-key", "model");
        String key = AiExplanationProviderRequests.key(config, "standard", "question", Collections.emptyList());
        assertEquals(64, key.length());
        assertFalse(key.contains(config.getApiKey()));
        assertNotEquals(key, AiExplanationProviderRequests.key(config, "refined", "question", Collections.emptyList()));
        assertNotEquals(key, AiExplanationProviderRequests.key(config, "standard", "question", Collections.singletonList("image")));
        assertNotEquals(key, AiExplanationProviderRequests.key(
                new ApiConfig(config.getBaseUrl(), "changed-key", config.getModel()), "standard", "question", Collections.emptyList()));
    }
}
