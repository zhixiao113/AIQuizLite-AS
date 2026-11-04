package com.example.aiquizlite;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/** A request outlives its dialog; callers for the same answer share its result. */
final class AiExplanationRequests {
    static final AiExplanationRequests SHARED = new AiExplanationRequests(Executors.newFixedThreadPool(2));
    interface Cache {
        AiExplanation get(String key);
        void put(String key, AiExplanation explanation);
    }
    interface Generator {
        AiExplanation generate() throws OpenAiCompatibleClient.AiClientException;
    }
    private final Executor executor;
    private final ConcurrentHashMap<String, FutureTask<AiExplanation>> running = new ConcurrentHashMap<>();

    AiExplanationRequests(Executor executor) { this.executor = executor; }

    AiExplanation get(String key, Cache cache, boolean force, Generator generator)
            throws OpenAiCompatibleClient.AiClientException {
        FutureTask<AiExplanation> task = new FutureTask<>(() -> {
            AiExplanation existing = force ? null : cache.get(key);
            if (existing != null) return existing;
            AiExplanation generated = generator.generate();
            cache.put(key, generated);
            return generated;
        });
        FutureTask<AiExplanation> previous = running.putIfAbsent(key, task);
        if (previous != null) task = previous;
        else {
            final FutureTask<AiExplanation> owned = task;
            executor.execute(() -> {
                try { owned.run(); }
                finally { running.remove(key, owned); }
            });
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiCompatibleClient.AiClientException(
                    OpenAiCompatibleClient.FailureKind.UNKNOWN, "讲解窗口已关闭，生成结果将在完成后保存", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OpenAiCompatibleClient.AiClientException)
                throw (OpenAiCompatibleClient.AiClientException) e.getCause();
            throw new OpenAiCompatibleClient.AiClientException(
                    OpenAiCompatibleClient.FailureKind.UNKNOWN, "AI 讲解生成失败，请重试", e.getCause());
        }
    }
}
