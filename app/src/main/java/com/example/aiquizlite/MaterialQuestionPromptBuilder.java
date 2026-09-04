package com.example.aiquizlite;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MaterialQuestionPromptBuilder {
    private MaterialQuestionPromptBuilder() {
    }

    public static String systemPrompt() {
        return "你是严谨的中文学习题库生成器。只能依据用户提供的资料出题，不得补充资料外事实。"
                + "只返回可解析的 JSON，不要 Markdown、说明或代码围栏。";
    }

    public static String generate(
            String bankName,
            String chapter,
            String source,
            Map<String, Integer> quotas
    ) {
        return commonRules(bankName, chapter, quotas)
                + "\n请严格生成上述数量的新题。每题必须能从资料中找到依据。"
                + "\n资料原文：\n" + source;
    }

    public static String fillMissing(
            String bankName,
            String chapter,
            String source,
            Map<String, Integer> missing,
            String existingStems
    ) {
        return commonRules(bankName, chapter, missing)
                + "\n这是定向补齐任务，只生成缺少的题，不要返回其他题型或额外题目。"
                + "\n不得与以下已有题干重复：\n" + existingStems
                + "\n资料原文：\n" + source;
    }

    public static String parsePasted(String pastedText, Map<String, Integer> missing) {
        return "把用户粘贴的现成题目结构化为 JSON 数组，保持题干和答案原文，不要重新创作。"
                + "解析数量不得超过缺口：" + quotaText(missing)
                + schemaRules()
                + "\n用户粘贴内容：\n" + pastedText;
    }

    public static String regenerate(
            String bankName,
            String chapter,
            String source,
            String type,
            String oldStem
    ) {
        Map<String, Integer> quota = new LinkedHashMap<>();
        quota.put(type, 1);
        return commonRules(bankName, chapter, quota)
                + "\n重新生成一道不同的题，不得与旧题干重复：" + oldStem
                + "\n资料原文：\n" + source;
    }

    private static String commonRules(
            String bankName,
            String chapter,
            Map<String, Integer> quotas
    ) {
        return "题库名称：" + bankName
                + "\n章节：" + chapter
                + "\n必须精确返回：" + quotaText(quotas)
                + schemaRules();
    }

    private static String schemaRules() {
        return "\n返回 JSON 数组。每项字段：type、stem、options、answers、explanation、sourceSnippet。"
                + "type 只能是 single、multi、judge、fill_blank。"
                + "options 是 [{\"key\":\"A\",\"text\":\"...\"}]；单选答案一个，多选至少两个。"
                + "判断题固定使用 A=对、B=错；填空题 options 必须为空数组，answers 只放纯答案，不带(1)等序号。"
                + "explanation 没有可靠解析时设为 null；sourceSnippet 放支持该题的简短原文。";
    }

    private static String quotaText(Map<String, Integer> quotas) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : quotas.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (result.length() > 0) {
                result.append('，');
            }
            result.append(typeLabel(entry.getKey())).append(' ').append(entry.getValue()).append(" 题");
        }
        return result.length() == 0 ? "0 题" : result.toString();
    }

    private static String typeLabel(String type) {
        if (MaterialQuestionRules.SINGLE.equals(type)) {
            return "单选";
        }
        if (MaterialQuestionRules.MULTI.equals(type)) {
            return "多选";
        }
        if (MaterialQuestionRules.JUDGE.equals(type)) {
            return "判断";
        }
        return "填空";
    }
}
