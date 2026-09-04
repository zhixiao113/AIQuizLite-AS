package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MaterialQuestionRules {
    public static final String SINGLE = "single";
    public static final String MULTI = "multi";
    public static final String JUDGE = "judge";
    public static final String FILL_BLANK = "fill_blank";

    private MaterialQuestionRules() {
    }

    public static ValidationResult validate(
            JSONArray candidates,
            Map<String, Integer> requestedQuotas,
            String fallbackPaper
    ) throws JSONException {
        Map<String, Integer> quotas = normalizedQuotas(requestedQuotas);
        Map<String, Integer> acceptedByType = zeroCounts(quotas);
        JSONArray accepted = new JSONArray();
        Set<String> seenStems = new HashSet<>();
        int duplicateCount = 0;
        int invalidCount = 0;
        int overflowCount = 0;

        JSONArray safeCandidates = candidates == null ? new JSONArray() : candidates;
        for (int i = 0; i < safeCandidates.length(); i++) {
            JSONObject candidate = safeCandidates.optJSONObject(i);
            NormalizedQuestion normalized = normalize(candidate, fallbackPaper);
            if (normalized == null || !quotas.containsKey(normalized.type)) {
                invalidCount++;
                continue;
            }
            String fingerprint = stemFingerprint(normalized.value.optString("stem", ""));
            if (fingerprint.isEmpty()) {
                invalidCount++;
                continue;
            }
            if (seenStems.contains(fingerprint)) {
                duplicateCount++;
                continue;
            }
            int current = acceptedByType.get(normalized.type);
            if (current >= quotas.get(normalized.type)) {
                overflowCount++;
                continue;
            }
            seenStems.add(fingerprint);
            acceptedByType.put(normalized.type, current + 1);
            normalized.value.put("id", "material_q" + (accepted.length() + 1));
            normalized.value.put("index", accepted.length() + 1);
            normalized.value.put("numberInPaper", accepted.length() + 1);
            accepted.put(normalized.value);
        }

        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : quotas.entrySet()) {
            missing.put(entry.getKey(), Math.max(0, entry.getValue() - acceptedByType.get(entry.getKey())));
        }
        return new ValidationResult(
                accepted,
                Collections.unmodifiableMap(acceptedByType),
                Collections.unmodifiableMap(missing),
                duplicateCount,
                invalidCount,
                overflowCount,
                safeCandidates.length()
        );
    }

    private static NormalizedQuestion normalize(JSONObject raw, String fallbackPaper) throws JSONException {
        if (raw == null) {
            return null;
        }
        String type = normalizeType(raw.optString("type", ""));
        String stem = raw.optString("stem", "").trim();
        JSONArray rawAnswers = raw.optJSONArray("answers");
        if (type == null || stem.isEmpty() || rawAnswers == null || rawAnswers.length() == 0) {
            return null;
        }

        JSONArray answers = new JSONArray();
        for (int i = 0; i < rawAnswers.length(); i++) {
            String answer = rawAnswers.optString(i, "").trim();
            if (answer.isEmpty()) {
                return null;
            }
            answers.put(type.equals(FILL_BLANK) ? answer : answer.toUpperCase(Locale.ROOT));
        }

        JSONArray options = new JSONArray();
        Set<String> optionKeys = new HashSet<>();
        JSONArray rawOptions = raw.optJSONArray("options");
        if (rawOptions != null) {
            for (int i = 0; i < rawOptions.length(); i++) {
                JSONObject option = rawOptions.optJSONObject(i);
                if (option == null) {
                    return null;
                }
                String key = option.optString("key", "").trim().toUpperCase(Locale.ROOT);
                String text = option.optString("text", "").trim();
                if (key.isEmpty() || text.isEmpty() || !optionKeys.add(key)) {
                    return null;
                }
                options.put(new JSONObject().put("key", key).put("text", text));
            }
        }

        if (!validAnswerShape(type, answers, optionKeys)) {
            return null;
        }
        JSONObject value = new JSONObject();
        value.put("paper", nonEmpty(raw.optString("paper", ""), fallbackPaper));
        value.put("type", type);
        value.put("stem", stem);
        value.put("options", options);
        value.put("answers", answers);
        value.put("image", JSONObject.NULL);
        String explanation = raw.isNull("explanation")
                ? ""
                : raw.optString("explanation", "").trim();
        value.put("explanation", explanation.isEmpty() ? JSONObject.NULL : explanation);
        copyOptional(raw, value, "sourceChapter");
        copyOptional(raw, value, "sourceSnippet");
        copyOptional(raw, value, "qualityState");
        return new NormalizedQuestion(type, value);
    }

    private static boolean validAnswerShape(String type, JSONArray answers, Set<String> optionKeys) {
        if (FILL_BLANK.equals(type)) {
            return optionKeys.isEmpty() && answers.length() == 1;
        }
        if (optionKeys.size() < 2) {
            return false;
        }
        if ((SINGLE.equals(type) || JUDGE.equals(type)) && answers.length() != 1) {
            return false;
        }
        if (MULTI.equals(type) && answers.length() < 2) {
            return false;
        }
        for (int i = 0; i < answers.length(); i++) {
            if (!optionKeys.contains(answers.optString(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> normalizedQuotas(Map<String, Integer> requested) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (requested == null) {
            return result;
        }
        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            String type = normalizeType(entry.getKey());
            int count = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (type != null && count > 0) {
                result.put(type, result.getOrDefault(type, 0) + count);
            }
        }
        return result;
    }

    private static Map<String, Integer> zeroCounts(Map<String, Integer> quotas) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String type : quotas.keySet()) {
            result.put(type, 0);
        }
        return result;
    }

    public static String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "single":
            case "single_choice":
            case "single-choice":
            case "单选":
            case "单选题":
                return SINGLE;
            case "multi":
            case "multiple":
            case "multiple_choice":
            case "multiple-choice":
            case "多选":
            case "多选题":
                return MULTI;
            case "judge":
            case "boolean":
            case "true_false":
            case "true-false":
            case "判断":
            case "判断题":
                return JUDGE;
            case "fill_blank":
            case "fill-blank":
            case "fillblank":
            case "blank":
            case "填空":
            case "填空题":
                return FILL_BLANK;
            default:
                return null;
        }
    }

    public static String stemFingerprint(String stem) {
        StringBuilder result = new StringBuilder();
        String safeStem = stem == null ? "" : stem.toLowerCase(Locale.ROOT);
        for (int i = 0; i < safeStem.length(); i++) {
            char value = safeStem.charAt(i);
            if (Character.isLetterOrDigit(value)) {
                result.append(value);
            }
        }
        return result.toString();
    }

    private static String nonEmpty(String first, String fallback) {
        String value = first == null ? "" : first.trim();
        if (!value.isEmpty()) {
            return value;
        }
        String safeFallback = fallback == null ? "" : fallback.trim();
        return safeFallback.isEmpty() ? "资料生成题库" : safeFallback;
    }

    private static void copyOptional(JSONObject from, JSONObject to, String key) throws JSONException {
        String value = from.isNull(key) ? "" : from.optString(key, "").trim();
        if (!value.isEmpty()) {
            to.put(key, value);
        }
    }

    private static final class NormalizedQuestion {
        final String type;
        final JSONObject value;

        NormalizedQuestion(String type, JSONObject value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class ValidationResult {
        private final JSONArray accepted;
        private final Map<String, Integer> acceptedByType;
        private final Map<String, Integer> missingByType;
        private final int duplicateCount;
        private final int invalidCount;
        private final int overflowCount;
        private final int candidateCount;

        ValidationResult(
                JSONArray accepted,
                Map<String, Integer> acceptedByType,
                Map<String, Integer> missingByType,
                int duplicateCount,
                int invalidCount,
                int overflowCount,
                int candidateCount
        ) {
            this.accepted = accepted;
            this.acceptedByType = acceptedByType;
            this.missingByType = missingByType;
            this.duplicateCount = duplicateCount;
            this.invalidCount = invalidCount;
            this.overflowCount = overflowCount;
            this.candidateCount = candidateCount;
        }

        public JSONArray getAccepted() {
            return accepted;
        }

        public Map<String, Integer> getAcceptedByType() {
            return acceptedByType;
        }

        public Map<String, Integer> getMissingByType() {
            return missingByType;
        }

        public int getDuplicateCount() {
            return duplicateCount;
        }

        public int getInvalidCount() {
            return invalidCount;
        }

        public int getOverflowCount() {
            return overflowCount;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public int getMissingCount() {
            int total = 0;
            for (int value : missingByType.values()) {
                total += value;
            }
            return total;
        }

        public boolean isComplete() {
            return getMissingCount() == 0;
        }
    }
}
