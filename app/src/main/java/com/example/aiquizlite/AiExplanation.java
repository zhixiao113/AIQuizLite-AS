package com.example.aiquizlite;

import org.json.JSONObject;

public final class AiExplanation {
    private final String conclusion;
    private final String reasoning;
    private final String commonMistake;
    private final String memoryTip;

    public AiExplanation(
            String conclusion,
            String reasoning,
            String commonMistake,
            String memoryTip
    ) {
        this.conclusion = clean(conclusion);
        this.reasoning = clean(reasoning);
        this.commonMistake = clean(commonMistake);
        this.memoryTip = clean(memoryTip);
    }

    public String getConclusion() {
        return conclusion;
    }

    public String getReasoning() {
        return reasoning;
    }

    public String getCommonMistake() {
        return commonMistake;
    }

    public String getMemoryTip() {
        return memoryTip;
    }

    public String toDisplayText() {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "结论", conclusion);
        appendSection(builder, "原因", reasoning);
        appendSection(builder, "易错点", commonMistake);
        appendSection(builder, "记忆方法", memoryTip);
        return builder.toString().trim();
    }

    public String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("conclusion", conclusion);
            object.put("reasoning", reasoning);
            object.put("common_mistake", commonMistake);
            object.put("memory_tip", memoryTip);
        } catch (Exception ignored) {
            return "{}";
        }
        return object.toString();
    }

    public static AiExplanation fromCachedJson(String raw) {
        try {
            JSONObject object = new JSONObject(raw);
            return fromJsonObject(object);
        } catch (Exception e) {
            return null;
        }
    }

    public static AiExplanation fromModelText(String raw) {
        String cleaned = stripCodeFence(raw);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return fromJsonObject(new JSONObject(cleaned.substring(start, end + 1)));
            } catch (Exception ignored) {
                // Some compatible providers ignore JSON-only instructions. Fall back to readable text.
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("AI服务返回了空内容");
        }
        return new AiExplanation("请结合下面的讲解理解本题。", cleaned, "", "");
    }

    private static AiExplanation fromJsonObject(JSONObject object) {
        AiExplanation explanation = new AiExplanation(
                firstNonEmpty(object, "conclusion", "结论"),
                firstNonEmpty(object, "reasoning", "reason", "原因"),
                firstNonEmpty(object, "common_mistake", "commonMistake", "易错点"),
                firstNonEmpty(object, "memory_tip", "memoryTip", "记忆方法")
        );
        if (explanation.conclusion.isEmpty()
                && explanation.reasoning.isEmpty()
                && explanation.commonMistake.isEmpty()
                && explanation.memoryTip.isEmpty()) {
            throw new IllegalArgumentException("AI服务返回的讲解格式无法识别");
        }
        return explanation;
    }

    private static String firstNonEmpty(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = clean(object.optString(key, ""));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String stripCodeFence(String raw) {
        String value = clean(raw);
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLine = value.indexOf('\n');
        int finalFence = value.lastIndexOf("```");
        if (firstLine >= 0 && finalFence > firstLine) {
            return value.substring(firstLine + 1, finalFence).trim();
        }
        return value;
    }

    private static void appendSection(StringBuilder builder, String title, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(title).append("：\n").append(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
