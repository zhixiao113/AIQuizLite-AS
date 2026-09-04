package com.example.aiquizlite;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MaterialDocumentAnalysis {
    public static final String LEARNING_MATERIAL = "learning_material";
    public static final String QUESTION_BANK = "question_bank";
    private static final int MAX_RECOMMENDED_QUESTIONS = 100;

    private final String documentKind;
    private final double confidence;
    private final String reason;
    private final int recommendedQuestionCount;
    private final int detectedQuestionCount;
    private final Map<String, Integer> typeQuotas;

    private MaterialDocumentAnalysis(
            String documentKind,
            double confidence,
            String reason,
            int recommendedQuestionCount,
            int detectedQuestionCount,
            Map<String, Integer> typeQuotas
    ) {
        this.documentKind = documentKind;
        this.confidence = confidence;
        this.reason = reason;
        this.recommendedQuestionCount = recommendedQuestionCount;
        this.detectedQuestionCount = detectedQuestionCount;
        this.typeQuotas = Collections.unmodifiableMap(new LinkedHashMap<>(typeQuotas));
    }

    public static MaterialDocumentAnalysis fromModelText(String response) throws Exception {
        String value = response == null ? "" : response.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI 没有返回资料分析结果");
        }
        JSONObject root = new JSONObject(value.substring(start, end + 1));
        String kind = normalizeKind(root.optString("documentKind", ""));
        if (kind == null) {
            throw new IllegalArgumentException("AI 无法判断这是学习资料还是现成题库");
        }
        double confidence = Math.max(0d, Math.min(1d, root.optDouble("confidence", 0.5d)));
        String reason = root.optString("reason", "").trim();
        int detected = Math.max(0, root.optInt("detectedQuestionCount", 0));
        int recommended = Math.max(0, root.optInt("recommendedQuestionCount", 0));
        Map<String, Integer> quotas = readTypeCounts(root.optJSONObject("typeCounts"));
        if (LEARNING_MATERIAL.equals(kind)) {
            if (sum(quotas) == 0 && recommended > 0) {
                quotas.putAll(MaterialGenerationPlan.recommendTypes(
                        Math.min(MAX_RECOMMENDED_QUESTIONS, recommended)
                ));
            } else if (sum(quotas) > MAX_RECOMMENDED_QUESTIONS) {
                quotas = capTypeCounts(quotas, MAX_RECOMMENDED_QUESTIONS);
            }
            recommended = sum(quotas);
        } else {
            recommended = 0;
        }
        return new MaterialDocumentAnalysis(
                kind,
                confidence,
                reason.isEmpty() ? "AI 已根据内容结构完成判断" : reason,
                recommended,
                detected,
                quotas
        );
    }

    public boolean isQuestionBank() {
        return QUESTION_BANK.equals(documentKind);
    }

    public String getDocumentKind() {
        return documentKind;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public int getRecommendedQuestionCount() {
        return recommendedQuestionCount;
    }

    public int getDetectedQuestionCount() {
        return detectedQuestionCount;
    }

    public Map<String, Integer> getTypeQuotas() {
        return typeQuotas;
    }

    private static String normalizeKind(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals(QUESTION_BANK)
                || value.equals("questionbank")
                || value.equals("questions")
                || value.contains("题库")
                || value.contains("试题")) {
            return QUESTION_BANK;
        }
        if (value.equals(LEARNING_MATERIAL)
                || value.equals("material")
                || value.equals("learning")
                || value.contains("资料")
                || value.contains("教材")) {
            return LEARNING_MATERIAL;
        }
        return null;
    }

    private static Map<String, Integer> readTypeCounts(JSONObject source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String type = MaterialQuestionRules.normalizeType(key);
            int count = Math.max(0, source.optInt(key, 0));
            if (type != null && count > 0) {
                result.put(type, result.getOrDefault(type, 0) + count);
            }
        }
        return result;
    }

    private static Map<String, Integer> capTypeCounts(Map<String, Integer> source, int maximum) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int sourceTotal = sum(source);
        int allocated = 0;
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            int value = (entry.getValue() * maximum) / sourceTotal;
            result.put(entry.getKey(), value);
            allocated += value;
        }
        while (allocated < maximum) {
            String best = null;
            double bestRemainder = -1d;
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                double exact = entry.getValue() * maximum / (double) sourceTotal;
                double remainder = exact - result.get(entry.getKey());
                if (remainder > bestRemainder) {
                    best = entry.getKey();
                    bestRemainder = remainder;
                }
            }
            result.put(best, result.get(best) + 1);
            allocated++;
        }
        return result;
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        for (int value : values.values()) {
            total += value;
        }
        return total;
    }
}
