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

    public static String analysisSystemPrompt() {
        return "你是严谨的学习资料分析器。判断文档主要是知识讲解资料还是已有题库，"
                + "并基于可考查知识点给出题量建议。只返回 JSON，不要 Markdown 或说明。";
    }

    public static String analyzeDocument(
            String sourceName,
            int totalCharacters,
            int chapterCount,
            String representativeText
    ) {
        return "文件名：" + sourceName
                + "\n全文字符数：" + totalCharacters
                + "\n本地识别的章节数：" + chapterCount
                + "\n请返回 JSON 对象："
                + "{\"documentKind\":\"learning_material 或 question_bank\","
                + "\"confidence\":0到1,\"reason\":\"简短理由\","
                + "\"recommendedQuestionCount\":整数,\"detectedQuestionCount\":整数,"
                + "\"typeCounts\":{\"single\":整数,\"multi\":整数,"
                + "\"judge\":整数,\"fill_blank\":整数}}。"
                + "\n判断规则：主要由题干、选项、答案或解析组成的是 question_bank；"
                + "主要讲解概念、步骤或知识的是 learning_material。"
                + "\n若是学习资料，题量必须根据不同且可考查的知识点决定，不得按字符长度机械估算；"
                + "typeCounts 合计必须等于 recommendedQuestionCount，范围 1 到 100。"
                + "若是题库，估算原题总数填 detectedQuestionCount，不要把示例题误判成整份题库。"
                + "\n以下是覆盖各章节的代表性内容：\n" + representativeText;
    }

    public static String analyzeLearningChunk(
            String sourceName,
            String chapter,
            String source
    ) {
        return "这是学习资料知识点盘点，不生成题目。"
                + "\n文件名：" + sourceName
                + "\n章节：" + chapter
                + "\n识别本片段中不同且值得考查的知识点，并建议每种题型的数量。"
                + "同一知识点不要因为换一种说法重复计数；简单事实通常只建议 1 题，"
                + "包含多个独立步骤或条件时才可建议多题。"
                + "\n只返回 JSON 对象：{\"documentKind\":\"learning_material\","
                + "\"confidence\":1,\"reason\":\"知识点概述\","
                + "\"recommendedQuestionCount\":整数,"
                + "\"typeCounts\":{\"single\":整数,\"multi\":整数,"
                + "\"judge\":整数,\"fill_blank\":整数}}。"
                + "typeCounts 合计必须等于 recommendedQuestionCount；没有可考查知识点时四项都返回 0。"
                + "\n资料片段：\n" + source;
    }

    public static String generate(
            String bankName,
            String chapter,
            String source,
            Map<String, Integer> quotas
    ) {
        return commonRules(bankName, chapter, quotas)
                + "\n请严格生成上述数量的新题。每题必须能从资料中找到依据。"
                + "先在内部区分本片段的不同知识点，每题对应一个明确知识点，"
                + "不要围绕同一事实仅换说法重复出题。"
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

    public static String convertQuestionBankChunk(
            String bankName,
            String chapter,
            String source
    ) {
        return "把下面现成题库片段中的所有完整题目忠实转换为 JSON 数组，不要重新创作，不要限制题量。"
                + "片段之间含有重叠内容；重复题可以返回，应用会统一去重。"
                + "只有在题干、选项和答案能够可靠对应时才返回该题；不要把页眉、分数、作答记录或 UI 文案当题目。"
                + "\n题库名称：" + bankName
                + "\n来源章节：" + chapter
                + schemaRules()
                + "\n遇到简答题且存在明确参考答案时转为 fill_blank；无法可靠转换的题型不要猜答案。"
                + "\n题库原文片段：\n" + source;
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
