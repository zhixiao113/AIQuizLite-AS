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
        StringBuilder builder = new StringBuilder();
        builder.append("题型：").append(question.typeLabel()).append('\n');
        builder.append("题目：").append(question.getStem()).append('\n');
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
}
