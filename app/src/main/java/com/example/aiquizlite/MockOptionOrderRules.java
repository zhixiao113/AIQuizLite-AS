package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

public final class MockOptionOrderRules {
    private static final Pattern LETTER_RELATION = Pattern.compile(
            "(^|[^A-Z0-9])[A-D]\\s*(和|与|、|,|，|/|及|或)\\s*[A-D]([^A-Z0-9]|$)"
    );
    private static final Pattern OPTION_LETTER = Pattern.compile(
            "(选项\\s*[A-D]|[A-D]\\s*选项|第\\s*[A-D]\\s*项)"
    );
    private static final Pattern ISOLATED_OPTION_LETTER = Pattern.compile(
            "(^|[^A-Z0-9])[A-D]([^A-Z0-9]|$)"
    );
    private static final String[] ORDER_SENSITIVE_WORDS = {
            "以上选项", "上述选项", "以下选项", "前两项", "后两项", "前者", "后者",
            "选项顺序", "按顺序", "依次", "都正确", "均正确", "皆正确", "都不正确",
            "以上都", "以上均", "上述都", "上述均"
    };

    private MockOptionOrderRules() {
    }

    public static boolean canShuffle(Question question) {
        if (question == null
                || question.isFillBlank()
                || "judge".equals(question.getType())
                || question.getOptions().size() < 2) {
            return false;
        }
        StringBuilder content = new StringBuilder(question.getStem());
        for (Question.Option option : question.getOptions()) {
            content.append('\n').append(option.getText());
        }
        return !referencesOptionLetterOrOrder(content.toString());
    }

    public static List<String> createOptionOrder(Question question, Random random) {
        List<String> keys = new ArrayList<>();
        for (Question.Option option : question.getOptions()) {
            keys.add(option.getKey());
        }
        if (canShuffle(question)) {
            Collections.shuffle(keys, random);
        }
        return keys;
    }

    static boolean referencesOptionLetterOrOrder(String raw) {
        String text = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        for (String word : ORDER_SENSITIVE_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return LETTER_RELATION.matcher(text).find()
                || OPTION_LETTER.matcher(text).find()
                || ISOLATED_OPTION_LETTER.matcher(text).find();
    }
}
