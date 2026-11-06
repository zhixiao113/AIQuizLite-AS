package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

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

        Result(AiExplanation explanation, String error, List<Failure> failures, boolean cached) {
            this.explanation = explanation;
            this.error = error;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.cached = cached;
        }

        static Result cached(AiExplanation explanation) {
            return new Result(explanation, null, Collections.emptyList(), true);
        }
    }

    private AiExplanationRouter() {}

    static Result generate(Pool pool, boolean needsVision, LongSupplier clock, Preparation preparation)
            throws OpenAiCompatibleClient.AiClientException {
        List<Failure> failures = new ArrayList<>();
        Attempt attempt = null;
        for (AiProfiles.Model saved : pool.models()) {
            AiProfiles.Model model = pool.model(saved.id);
            if (!eligible(model, needsVision, clock.getAsLong())) continue;
            if (attempt == null) {
                attempt = preparation.prepare();
                // Reading a local question image may take time. Recheck before sending it.
                model = pool.model(saved.id);
                if (!eligible(model, needsVision, clock.getAsLong())) continue;
            }
            try {
                AiExplanation explanation = attempt.generate(model.config);
                return new Result(explanation, null, failures, false);
            } catch (OpenAiCompatibleClient.AiClientException error) {
                if (Thread.currentThread().isInterrupted()) throw error;
                boolean cooled = pool.markFailed(model, clock.getAsLong());
                failures.add(new Failure(model, error, cooled));
            }
        }
        String message;
        if (!failures.isEmpty()) {
            message = "可用模型均调用失败，请检查 AI 设置";
        } else if (needsVision) {
            message = "请配置识图模型。添加或测试支持图片输入的模型，并确认已启用";
        } else {
            message = "没有可用的 AI 模型，请在 AI 设置中添加、启用或测试模型";
        }
        return new Result(null, message, failures, false);
    }

    private static boolean eligible(AiProfiles.Model model, boolean needsVision, long now) {
        return model != null && model.enabled && model.config.isComplete() && !model.isCooling(now)
                && (!needsVision || model.visionSupported);
    }
}
