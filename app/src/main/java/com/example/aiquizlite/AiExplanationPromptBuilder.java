package com.example.aiquizlite;

import java.util.List;

public final class AiExplanationPromptBuilder {
    private AiExplanationPromptBuilder() {
    }

    public static String systemPrompt() {
        return "你是中文考试辅导老师。只能根据题目、用户答案、正确答案和题库原解析进行讲解，"
                + "不得修改或质疑给定正确答案。讲解要准确、清晰、便于记忆。"
                + "只返回一个JSON对象，不要Markdown代码块，不要额外文字。"
                + "JSON必须包含字符串字段 conclusion、reasoning、common_mistake、memory_tip。";
    }

    public static String userPrompt(
            Question question,
            List<String> selectedAnswers,
            boolean correct,
            String formattedUserAnswer,
            String formattedCorrectAnswer
    ) {
        return userPrompt(question, selectedAnswers, correct, formattedUserAnswer,
                formattedCorrectAnswer, null, null);
    }

    public static String userPrompt(
            Question question,
            List<String> selectedAnswers,
            boolean correct,
            String formattedUserAnswer,
            String formattedCorrectAnswer,
            List<MatchingData.Pair> selectedPairs,
            List<MatchingData.Pair> correctPairs
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("题型：").append(question.typeLabel()).append('\n');
        builder.append("题目：").append(question.getStem()).append('\n');
        if (question.isMatching()) {
            builder.append("左侧项目：\n");
            for (MatchingData.Item item : question.getMatching().getLeftItems()) {
                builder.append(item.getId()).append("：").append(item.getText()).append('\n');
            }
            builder.append("右侧项目：\n");
            for (MatchingData.Item item : question.getMatching().getRightItems()) {
                builder.append(item.getId()).append("：").append(item.getText()).append('\n');
            }
            builder.append("用户连线：\n").append(formatPairs(question, selectedPairs));
            builder.append("标准连线：\n").append(formatPairs(question, correctPairs));
            builder.append("作答结果：").append(correct ? "正确" : "错误").append('\n');
            builder.append("题库原解析：")
                    .append(question.getExplanation() == null ? "无" : question.getExplanation())
                    .append('\n');
            builder.append("只解释已给出的标准关系；逐个右侧项目说明易错点。"
                    + "不要把连线当作选择题字母，不要推断未提供的题图，也不要修改标准答案。");
            return builder.toString();
        }
        if (!question.getOptions().isEmpty()) {
            builder.append("选项：\n");
            for (Question.Option option : question.getOptions()) {
                builder.append(option.displayText()).append('\n');
            }
        }
        builder.append("用户答案：").append(formattedUserAnswer).append('\n');
        builder.append("作答结果：").append(correct ? "正确" : "错误").append('\n');
        builder.append("正确答案：").append(formattedCorrectAnswer).append('\n');
        builder.append("题库原解析：")
                .append(question.getExplanation() == null ? "无" : question.getExplanation())
                .append('\n');
        builder.append("请生成适合复习的结构化讲解。易错点要针对本题，记忆方法要简短。\n");
        builder.append("用户原始答案数量：").append(selectedAnswers == null ? 0 : selectedAnswers.size());
        return builder.toString();
    }

    private static String formatPairs(Question question, List<MatchingData.Pair> pairs) {
        if (pairs == null || pairs.isEmpty()) return "未作答\n";
        StringBuilder result = new StringBuilder();
        for (MatchingData.Pair pair : pairs) {
            result.append(question.getMatching().rightText(pair.getRightId()))
                    .append(" → ")
                    .append(question.getMatching().leftText(pair.getLeftId())).append('\n');
        }
        return result.toString();
    }
}
