package com.example.aiquizlite;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Probe with a synthetic picture, never a user's question or a name-based assumption. */
final class AiModelProbe {
    private static final Pattern FOUR_DIGITS = Pattern.compile("(?<![0-9])[0-9]{4}(?![0-9])");
    private static final String[] ANSWERS = {"4736", "8291", "5072", "1638"};
    private static final String[] PROBE_PNGS = {
        "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"
    };

    static final class Result {
        private final String fingerprint;
        final boolean visionTested;
        final boolean visionSupported;

        private Result(ApiConfig config, boolean visionTested, boolean visionSupported) {
            fingerprint = ApiConfigStore.fingerprint(config);
            this.visionTested = visionTested;
            this.visionSupported = visionSupported;
        }

        boolean matches(ApiConfig config) {
            return config != null && config.isComplete() && fingerprint.equals(ApiConfigStore.fingerprint(config));
        }
    }

    private AiModelProbe() {}

    static Result test(ApiConfig config, OpenAiCompatibleClient client) throws OpenAiCompatibleClient.AiClientException {
        return test(config, client, ThreadLocalRandom.current().nextInt(ANSWERS.length));
    }

    static Result test(ApiConfig config, OpenAiCompatibleClient client, int probeIndex)
            throws OpenAiCompatibleClient.AiClientException {
        client.testConnection(config);
        if (Thread.currentThread().isInterrupted()) {
            throw new OpenAiCompatibleClient.AiClientException("连接测试已取消");
        }
        try {
            String answer = client.testVision(config, "data:image/png;base64," + PROBE_PNGS[probeIndex]);
            Matcher digits = FOUR_DIGITS.matcher(answer);
            boolean recognized = digits.find() && ANSWERS[probeIndex].equals(digits.group()) && !digits.find();
            return new Result(config, true, recognized);
        } catch (OpenAiCompatibleClient.AiClientException error) {
            int status = error.getHttpStatus();
            boolean unsupported = status == 400 || status == 415 || status == 422;
            return new Result(config, unsupported, false);
        }
    }
}
