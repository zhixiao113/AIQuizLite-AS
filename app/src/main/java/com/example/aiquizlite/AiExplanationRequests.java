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
        AiExplanationRouter.Result generate() throws OpenAiCompatibleClient.AiClientException;
    }
    private final Executor executor;
    private final ConcurrentHashMap<String, FutureTask<AiExplanationRouter.Result>> running = new ConcurrentHashMap<>();

    AiExplanationRequests(Executor executor) { this.executor = executor; }

    AiExplanationRouter.Result get(String key, Cache cache, boolean force, Generator generator)
            throws OpenAiCompatibleClient.AiClientException {
        FutureTask<AiExplanationRouter.Result> task = new FutureTask<>(() -> {
            AiExplanation existing = force ? null : cache.get(key);
            if (existing != null) return AiExplanationRouter.Result.cached(existing);
            AiExplanationRouter.Result generated = generator.generate();
            if (generated.explanation != null) cache.put(key, generated.explanation);
            return generated;
        });
        FutureTask<AiExplanationRouter.Result> previous = running.putIfAbsent(key, task);
        if (previous != null) task = previous;
        else {
            final FutureTask<AiExplanationRouter.Result> owned = task;
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
