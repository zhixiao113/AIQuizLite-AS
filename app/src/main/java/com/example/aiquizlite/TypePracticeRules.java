package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TypePracticeRules {
    static final List<String> TYPES = Collections.unmodifiableList(
            Arrays.asList("single", "multi", "judge", "fill_blank", "matching"));
    static String label(String type) {
        switch (type) {
            case "single": return "单选题";
            case "multi": return "多选题";
            case "judge": return "判断题";
            case "fill_blank": return "填空题";
            case "matching": return "连线题";
            default: throw new IllegalArgumentException("请选择支持的练习题型");
        }
    }
    static List<Question> filter(List<Question> source, String type) {
        sessionKey("validation", type);
        List<Question> result = new ArrayList<>();
        for (Question question : source) if (type.equals(question.getType())) result.add(question);
        return result;
    }
    static String sessionKey(String bankId, String type) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("请选择支持的练习题型");
        return "type_session_" + type + "_" + bankId;
    }
}
