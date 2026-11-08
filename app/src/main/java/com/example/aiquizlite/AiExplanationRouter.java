package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Tries saved models in creation order and leaves local preparation failures outside cooldown. */
final class AiExplanationRouter {
    interface Pool {
        List<AiProfiles.Model> models();
        AiProfiles.Model model(String id);
        boolean markFailed(AiProfiles.Model attempted, long now);
    }

    interface Preparation {
        Attempt prepare() throws OpenAiCompatibleClient.AiClientException;
    }

    interface Attempt {
        AiExplanation generate(ApiConfig config) throws OpenAiCompatibleClient.AiClientException;
    }

    static final class Failure {
        final String profileId;
        final String modelId;
        final String reason;
        final boolean cooled;

        Failure(AiProfiles.Model model, OpenAiCompatibleClient.AiClientException error, boolean cooled) {
            profileId = model.id;
            modelId = model.config.getModel();
            reason = error.getMessage();
            this.cooled = cooled;
        }
    }

    static final class Result {
        final AiExplanation explanation;
        final String error;
        final List<Failure> failures;
        final boolean cached;
        final String profileId;
        final String modelId;
        final AiProfiles.Model model;

        Result(AiExplanation explanation, String error, List<Failure> failures, boolean cached) {
            this(explanation, error, failures, cached, null);
        }

        Result(AiExplanation explanation, String error, List<Failure> failures, boolean cached,
                AiProfiles.Model model) {
            this.explanation = explanation;
            this.error = error;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.cached = cached;
            this.profileId = explanation == null ? "" : explanation.getProfileId();
            this.modelId = explanation == null ? "" : explanation.getModelId();
            this.model = model;
        }

        static Result cached(AiExplanation explanation) {
            return new Result(explanation, null, Collections.emptyList(), true);
        }
    }

    private AiExplanationRouter() {}

    static Result generate(Pool pool, boolean needsVision, LongSupplier clock, Preparation preparation)
            throws OpenAiCompatibleClient.AiClientException {
        return generate(pool, needsVision, clock, preparation, null, false, null);
    }

    static List<AiProfiles.Model> eligibleModels(Pool pool, boolean needsVision, long now) {
        List<AiProfiles.Model> models = new ArrayList<>();
        for (AiProfiles.Model model : pool.models()) {
            if (eligible(model, needsVision, now)) models.add(model);
        }
        return models;
    }

    static Result generate(Pool pool, boolean needsVision, LongSupplier clock, Preparation preparation,
            String preferredProfileId, boolean pinned, Consumer<AiProfiles.Model> onAttempt)
            throws OpenAiCompatibleClient.AiClientException {
        List<Failure> failures = new ArrayList<>();
        Attempt attempt = null;
        List<AiProfiles.Model> candidates = eligibleModels(pool, needsVision, clock.getAsLong());
        AiProfiles.Model preferred = preferredProfileId == null ? null : pool.model(preferredProfileId);
        if (eligible(preferred, needsVision, clock.getAsLong())) {
            candidates.removeIf(model -> model.id.equals(preferredProfileId));
            candidates.add(0, preferred);
        }
        for (AiProfiles.Model saved : candidates) {
            if (Thread.currentThread().isInterrupted()) throw cancelled();
            AiProfiles.Model model = pool.model(saved.id);
            if (!eligible(model, needsVision, clock.getAsLong())) continue;
            if (attempt == null) {
                attempt = preparation.prepare();
                // Reading a local question image may take time. Recheck before sending it.
                model = pool.model(saved.id);
                if (!eligible(model, needsVision, clock.getAsLong())) continue;
            }
            if (onAttempt != null) onAttempt.accept(model);
            try {
                AiExplanation explanation = attempt.generate(model.config).withSource(model.id, model.config.getModel());
                return new Result(explanation, null, failures, false, model);
            } catch (OpenAiCompatibleClient.AiClientException error) {
                if (error.isCancelled() || Thread.currentThread().isInterrupted()) throw error;
                boolean cooled = error.shouldCooldown() && pool.markFailed(model, clock.getAsLong());
                failures.add(new Failure(model, error, cooled));
                // A user's explicit choice must fail visibly, preserving the previous explanation.
                if (pinned && model.id.equals(preferredProfileId)) break;
            }
        }
        String message;
        if (pinned && !failures.isEmpty() && failures.get(0).profileId.equals(preferredProfileId)) {
            message = failures.get(0).reason;
        } else if (!failures.isEmpty()) {
            message = "所有模型均调用失败，请稍后重试或前往 AI 设置检查配置";
        } else if (needsVision) {
            message = "请配置识图模型后使用图片题目讲解功能";
        } else {
            message = "暂无可用 AI 模型，请前往 AI 设置配置后使用";
        }
        return new Result(null, message, failures, false);
    }

    static boolean eligible(AiProfiles.Model model, boolean needsVision, long now) {
        return model != null && model.enabled && model.config.isComplete() && !model.isCooling(now)
                && (!needsVision || model.visionSupported);
    }

    private static OpenAiCompatibleClient.AiClientException cancelled() {
        return new OpenAiCompatibleClient.AiClientException(OpenAiCompatibleClient.FailureKind.CANCELLED,
                "AI 请求已取消");
    }
}
