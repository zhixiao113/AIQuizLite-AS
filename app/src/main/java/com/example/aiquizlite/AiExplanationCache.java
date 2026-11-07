package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class AiExplanationCache implements AiExplanationRequests.Cache {
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

    static String modelKeyFor(String questionKey, String profileId) {
        // Keep the old question key readable for migration, and version the new term-aware format.
        return sha256("explanation-terms-v1\n" + questionKey + "\n" + profileId);
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
        java.util.List<Question.Option> options = new java.util.ArrayList<>(question.getOptions());
        options.sort(java.util.Comparator.comparing(Question.Option::getKey));
        for (Question.Option option : options) {
            value.append(option.getKey()).append('=').append(option.getText()).append('\n');
        }
        value.append(canonical(correctAnswers, !question.isFillBlank())).append('\n')
                .append(canonical(selectedAnswers, !question.isFillBlank())).append('\n')
                .append(question.getImage()).append("\n").append(question.getExplanationImages()).append('\n');
        if (question.isMatching()) {
            MatchingData matching = question.getMatching();
            value.append(matching.getMode()).append('\n');
            appendItems(value, matching.getLeftItems());
            appendItems(value, matching.getRightItems());
            value.append(canonical(matching.getCorrectPairs(), true));
            value.append('\n').append(canonical(correctPairs, true)).append('\n').append(canonical(selectedPairs, true)).append('\n');
        }
        value.append(correct).append('\n')
                .append(question.getExplanation()).append('\n')
                .append("explanation-v2");
        return sha256(value.toString());
    }

    static String legacyKeyFor(String questionBankId, Question question, List<String> selectedAnswers,
            List<String> correctAnswers, List<MatchingData.Pair> selectedPairs,
            List<MatchingData.Pair> correctPairs, boolean correct, ApiConfig config) {
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

    private static String canonical(List<?> values, boolean sort) {
        java.util.List<String> items = new java.util.ArrayList<>();
        if (values != null) for (Object value : values) items.add(String.valueOf(value));
        if (sort) java.util.Collections.sort(items);
        return new org.json.JSONArray(items).toString();
    }

    private static void appendItems(StringBuilder value, List<MatchingData.Item> items) {
        java.util.List<MatchingData.Item> sorted = new java.util.ArrayList<>(items);
        sorted.sort(java.util.Comparator.comparing(MatchingData.Item::getId));
        org.json.JSONArray array = new org.json.JSONArray();
        for (MatchingData.Item item : sorted) {
            array.put(new org.json.JSONArray().put(item.getId()).put(item.getText()));
        }
        value.append(array).append('\n');
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
