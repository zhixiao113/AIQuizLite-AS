package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MaterialManualQuestionParser {
    private static final Pattern QUESTION_START = Pattern.compile("^\\s*\\d{1,4}[.、．)]\\s*(.+)$");
    private static final Pattern OPTION = Pattern.compile("^\\s*([A-Ha-h])[.、．:：|)]\\s*(.+)$");
    private static final Pattern ANSWER = Pattern.compile("^\\s*(?:正确答案|参考答案|答案)\\s*[:：]\\s*(.*)$");
    private static final Pattern EXPLANATION = Pattern.compile("^\\s*(?:答案解析|解析)\\s*[:：]\\s*(.*)$");

    private MaterialManualQuestionParser() {
    }

    public static JSONArray parse(String pastedText) throws Exception {
        String text = MaterialTextRules.normalizeText(pastedText);
        if (text.isEmpty()) {
            return new JSONArray();
        }
        if (text.startsWith("[") || text.startsWith("{") || text.startsWith("```")) {
            try {
                return QuestionRepository.normalizeImportedJson(text);
            } catch (Exception ignored) {
            }
        }
        List<List<String>> blocks = splitBlocks(text);
        JSONArray result = new JSONArray();
        for (List<String> block : blocks) {
            JSONObject parsed = parseBlock(block);
            if (parsed != null) {
                result.put(parsed);
            }
        }
        return result;
    }

    private static List<List<String>> splitBlocks(String text) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher start = QUESTION_START.matcher(line);
            if (start.matches()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                }
                current = new ArrayList<>();
                current.add(start.group(1).trim());
            } else {
                current.add(line.trim());
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    private static JSONObject parseBlock(List<String> lines) throws Exception {
        StringBuilder stem = new StringBuilder();
        JSONArray options = new JSONArray();
        List<String> optionKeys = new ArrayList<>();
        List<String> optionTexts = new ArrayList<>();
        String answerText = "";
        StringBuilder explanation = new StringBuilder();
        boolean readingExplanation = false;
        boolean readingUserAnswer = false;
        boolean awaitingCorrectAnswer = false;
        String typeHint = "";
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("判断题")) {
                typeHint = MaterialQuestionRules.JUDGE;
            } else if (line.contains("多选题")) {
                typeHint = MaterialQuestionRules.MULTI;
            } else if (line.contains("单选题")) {
                typeHint = MaterialQuestionRules.SINGLE;
            } else if (line.contains("填空题")) {
                typeHint = MaterialQuestionRules.FILL_BLANK;
            }
            if (line.matches("^\\s*我的答案\\s*[:：]?.*$")) {
                readingUserAnswer = true;
                awaitingCorrectAnswer = false;
                continue;
            }
            Matcher answer = ANSWER.matcher(line);
            if (answer.matches()) {
                readingUserAnswer = false;
                answerText = answer.group(1).trim();
                awaitingCorrectAnswer = answerText.isEmpty();
                readingExplanation = false;
                continue;
            }
            Matcher explanationStart = EXPLANATION.matcher(line);
            if (explanationStart.matches()) {
                readingExplanation = true;
                append(explanation, explanationStart.group(1).trim());
                continue;
            }
            if (readingUserAnswer) {
                continue;
            }
            if (awaitingCorrectAnswer) {
                if (line.matches("^[（(]?\\s*\\d+\\s*[)）]?$")) {
                    continue;
                }
                answerText = line.trim();
                awaitingCorrectAnswer = false;
                continue;
            }
            if (readingExplanation) {
                append(explanation, line);
                continue;
            }
            Matcher option = OPTION.matcher(line);
            if (option.matches()) {
                String key = option.group(1).toUpperCase(Locale.ROOT);
                String value = option.group(2).trim();
                optionKeys.add(key);
                optionTexts.add(value);
                options.put(new JSONObject().put("key", key).put("text", value));
                continue;
            }
            append(stem, removeTypePrefix(line));
        }
        if (stem.length() == 0 || answerText.isEmpty()) {
            return null;
        }

        String type = resolveType(typeHint, optionKeys, answerText);
        JSONArray answers = resolveAnswers(type, answerText, optionKeys, optionTexts);
        if (answers.length() == 0) {
            return null;
        }
        if (MaterialQuestionRules.JUDGE.equals(type) && options.length() == 0) {
            options.put(new JSONObject().put("key", "A").put("text", "对"));
            options.put(new JSONObject().put("key", "B").put("text", "错"));
        }
        if (MaterialQuestionRules.FILL_BLANK.equals(type)) {
            options = new JSONArray();
        }
        return new JSONObject()
                .put("type", type)
                .put("stem", stem.toString().trim())
                .put("options", options)
                .put("answers", answers)
                .put("explanation", explanation.length() == 0 ? JSONObject.NULL : explanation.toString().trim())
                .put("qualityState", "手动补题");
    }

    private static String resolveType(String hint, List<String> optionKeys, String answer) {
        if (!hint.isEmpty()) {
            return hint;
        }
        if (optionKeys.isEmpty()) {
            String normalized = answer.trim();
            if ("对".equals(normalized) || "错".equals(normalized)
                    || "正确".equals(normalized) || "错误".equals(normalized)) {
                return MaterialQuestionRules.JUDGE;
            }
            return MaterialQuestionRules.FILL_BLANK;
        }
        String letters = answer.toUpperCase(Locale.ROOT).replaceAll("[^A-H]", "");
        return letters.length() > 1 ? MaterialQuestionRules.MULTI : MaterialQuestionRules.SINGLE;
    }

    private static JSONArray resolveAnswers(
            String type,
            String answerText,
            List<String> optionKeys,
            List<String> optionTexts
    ) {
        JSONArray result = new JSONArray();
        String answer = answerText.trim().replaceFirst("^[（(]\\s*\\d+\\s*[)）]\\s*", "");
        if (MaterialQuestionRules.FILL_BLANK.equals(type)) {
            if (!answer.isEmpty()) {
                result.put(answer);
            }
            return result;
        }
        if (MaterialQuestionRules.JUDGE.equals(type)) {
            if ("对".equals(answer) || "正确".equals(answer) || "A".equalsIgnoreCase(answer)) {
                result.put("A");
            } else if ("错".equals(answer) || "错误".equals(answer) || "B".equalsIgnoreCase(answer)) {
                result.put("B");
            }
            return result;
        }
        String upper = answer.toUpperCase(Locale.ROOT);
        String letters = upper.replaceAll("[^A-H]", "");
        for (String key : optionKeys) {
            if (letters.contains(key)) {
                result.put(key);
            }
        }
        if (result.length() == 0) {
            for (int i = 0; i < optionTexts.size(); i++) {
                if (answer.equals(optionTexts.get(i))) {
                    result.put(optionKeys.get(i));
                    break;
                }
            }
        }
        return result;
    }

    private static String removeTypePrefix(String line) {
        return line.replaceFirst("^\\s*[（(\\[]?(?:单选题|多选题|判断题|填空题)[）)\\]]?\\s*", "");
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value);
    }
}
