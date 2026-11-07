package com.example.aiquizlite;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Shares an actual provider call even when separate dialogs reached it by different fallback routes. */
final class AiExplanationProviderRequests {
    interface Generation {
        AiExplanation run() throws OpenAiCompatibleClient.AiClientException;
    }

    private static final ConcurrentHashMap<String, FutureTask<AiExplanation>> RUNNING = new ConcurrentHashMap<>();

    private AiExplanationProviderRequests() {}

    static AiExplanation get(String key, Generation generation) throws OpenAiCompatibleClient.AiClientException {
        FutureTask<AiExplanation> task = new FutureTask<>(generation::run);
        FutureTask<AiExplanation> previous = RUNNING.putIfAbsent(key, task);
        boolean owner = previous == null;
        if (owner) task.run();
        else task = previous;
        try {
            return task.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OpenAiCompatibleClient.AiClientException(OpenAiCompatibleClient.FailureKind.CANCELLED,
                    "AI 请求已取消", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof OpenAiCompatibleClient.AiClientException) {
                throw (OpenAiCompatibleClient.AiClientException) error.getCause();
            }
            throw new OpenAiCompatibleClient.AiClientException(OpenAiCompatibleClient.FailureKind.UNKNOWN,
                    "AI 讲解生成失败，请重试", error.getCause());
        } finally {
            if (owner) RUNNING.remove(key, task);
        }
    }

    static String key(ApiConfig config, String system, String prompt, List<String> images) {
        List<String> parts = new ArrayList<>();
        parts.add(config.getBaseUrl());
        parts.add(config.getModel());
        parts.add(config.getApiKey());
        parts.add(system);
        parts.add(prompt);
        parts.addAll(images);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
