package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class AiExplanationCache {
    static final String PREF_NAME = "ai_explanation_cache";
    private final SharedPreferences preferences;

    public AiExplanationCache(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public AiExplanation get(String key) {
        String raw = preferences.getString(key, null);
        return raw == null ? null : AiExplanation.fromCachedJson(raw);
    }

    public void put(String key, AiExplanation explanation) {
        preferences.edit().putString(key, explanation.toJson()).apply();
    }

    public int count() {
        return preferences.getAll().size();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    public static String keyFor(
            String questionBankId,
            Question question,
            List<String> selectedAnswers,
            boolean correct,
            ApiConfig config
    ) {
        return keyFor(
                questionBankId,
                question,
                selectedAnswers,
                question.getAnswers(),
                correct,
                config
        );
    }

    public static String keyFor(
            String questionBankId,
            Question question,
            List<String> selectedAnswers,
            List<String> correctAnswers,
            boolean correct,
            ApiConfig config
    ) {
        return keyFor(questionBankId, question, selectedAnswers, correctAnswers,
                null, null, correct, config);
    }

    public static String keyFor(
            String questionBankId,
            Question question,
            List<String> selectedAnswers,
            List<String> correctAnswers,
            List<MatchingData.Pair> selectedPairs,
            List<MatchingData.Pair> correctPairs,
            boolean correct,
            ApiConfig config
    ) {
        StringBuilder value = new StringBuilder();
        value.append(questionBankId).append('\n')
                .append(question.getId()).append('\n')
                .append(question.getType()).append('\n')
                .append(question.getStem()).append('\n');
        for (Question.Option option : question.getOptions()) {
            value.append(option.getKey()).append('=').append(option.getText()).append('\n');
        }
        value.append(correctAnswers).append('\n')
                .append(selectedAnswers).append('\n')
                .append(question.getExplanationImages()).append('\n');
        if (question.isMatching()) {
            try { value.append(question.getMatching().toJson()); }
            catch (Exception invalid) { value.append(question.getMatching().getCorrectPairs()); }
            value.append('\n').append(correctPairs).append('\n').append(selectedPairs).append('\n');
        }
        value.append(correct).append('\n')
                .append(question.getExplanation()).append('\n')
                .append(AiEndpoint.normalizeBaseUrl(config.getBaseUrl())).append('\n')
                .append(config.getModel());
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
