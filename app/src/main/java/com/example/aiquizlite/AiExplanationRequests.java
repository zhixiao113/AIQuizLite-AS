package com.example.aiquizlite;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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
    private final AtomicLong requestOrder = new AtomicLong();
    private final Map<String, Long> newestSaved = new HashMap<>();

    AiExplanationRequests(Executor executor) { this.executor = executor; }

    AiExplanationRouter.Result get(String key, Cache cache, boolean force, Generator generator)
            throws OpenAiCompatibleClient.AiClientException {
        return get(key, key, cache, force, generator, explanation -> key);
    }

    AiExplanationRouter.Result getForModel(String questionKey, String profileId, Cache cache,
            boolean force, Generator generator) throws OpenAiCompatibleClient.AiClientException {
        String key = AiExplanationCache.modelKeyFor(questionKey, profileId);
        return get(key + (force ? ":regenerate" : ":initial"), key, cache, force, generator,
                explanation -> AiExplanationCache.modelKeyFor(questionKey,
                        explanation.getProfileId().isEmpty() ? profileId : explanation.getProfileId()));
    }

    AiExplanationRouter.Result getForModel(String questionKey, AiProfiles.Model requested, Cache cache,
            boolean force, Generator generator) throws OpenAiCompatibleClient.AiClientException {
        return getForModel(questionKey, requested, cache, force, false, generator);
    }

    AiExplanationRouter.Result getForModel(String questionKey, AiProfiles.Model requested, Cache cache,
            boolean force, boolean pinned, Generator generator) throws OpenAiCompatibleClient.AiClientException {
        Cache currentModelCache = new Cache() {
            @Override public AiExplanation get(String key) {
                AiExplanation found = cache.get(key);
                return found != null && requested.id.equals(found.getProfileId())
                        && requested.config.getModel().equals(found.getModelId()) ? found : null;
            }

            @Override public void put(String key, AiExplanation explanation) {
                cache.put(key, explanation);
            }
        };
        String key = AiExplanationCache.modelKeyFor(questionKey, requested.id);
        return get(key + (force ? ":regenerate:" : ":initial:") + requested.revision + ":" + pinned,
                key, currentModelCache, force, generator,
                explanation -> AiExplanationCache.modelKeyFor(questionKey,
                        explanation.getProfileId().isEmpty() ? requested.id : explanation.getProfileId()));
    }

    private AiExplanationRouter.Result get(String requestKey, String cacheKey, Cache cache,
            boolean force, Generator generator, Function<AiExplanation, String> resultKey)
            throws OpenAiCompatibleClient.AiClientException {
        long order = requestOrder.incrementAndGet();
        FutureTask<AiExplanationRouter.Result> task = new FutureTask<>(() -> {
            AiExplanation existing = force ? null : cache.get(cacheKey);
            if (existing != null) return AiExplanationRouter.Result.cached(existing);
            AiExplanationRouter.Result generated = generator.generate();
            if (generated.explanation != null) {
                String key = resultKey.apply(generated.explanation);
                synchronized (newestSaved) {
                    Long saved = newestSaved.get(key);
                    if (saved == null || order > saved) {
                        cache.put(key, generated.explanation);
                        newestSaved.put(key, order);
                    }
                }
            }
            return generated;
        });
        FutureTask<AiExplanationRouter.Result> previous = running.putIfAbsent(requestKey, task);
        if (previous != null) task = previous;
        else {
            final FutureTask<AiExplanationRouter.Result> owned = task;
            executor.execute(() -> {
                try { owned.run(); }
                finally { running.remove(requestKey, owned); }
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
