package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionQuestion {
    private final Question question;
    private final List<Question.Option> displayOptions;
    private final boolean relabelDisplayOptions;

    public SessionQuestion(Question question, boolean shuffleOptions) {
        this.question = question;
        this.displayOptions = new ArrayList<>(question.getOptions());
        if (shuffleOptions) {
            Collections.shuffle(this.displayOptions);
        }
        this.relabelDisplayOptions = false;
    }

    public SessionQuestion(
            Question question,
            List<String> optionKeyOrder,
            boolean relabelDisplayOptions
    ) {
        this.question = question;
        this.displayOptions = orderOptions(question.getOptions(), optionKeyOrder);
        this.relabelDisplayOptions = relabelDisplayOptions;
    }

    public Question getQuestion() {
        return question;
    }

    public List<Question.Option> getDisplayOptions() {
        return Collections.unmodifiableList(displayOptions);
    }

    public String displayText(Question.Option option) {
        if (!relabelDisplayOptions) {
            return option.displayText();
        }
        int index = displayOptions.indexOf(option);
        return displayLabel(index) + ". " + option.getText();
    }

    public String getDisplayLabelForOriginalKey(String originalKey) {
        for (int index = 0; index < displayOptions.size(); index++) {
            if (displayOptions.get(index).getKey().equals(originalKey)) {
                return displayLabel(index);
            }
        }
        return originalKey;
    }

    private static List<Question.Option> orderOptions(
            List<Question.Option> options,
            List<String> optionKeyOrder
    ) {
        Map<String, Question.Option> byKey = new HashMap<>();
        for (Question.Option option : options) {
            byKey.put(option.getKey(), option);
        }
        List<Question.Option> ordered = new ArrayList<>();
        if (optionKeyOrder != null) {
            for (String key : optionKeyOrder) {
                Question.Option option = byKey.remove(key);
                if (option != null) {
                    ordered.add(option);
                }
            }
        }
        for (Question.Option option : options) {
            if (byKey.containsKey(option.getKey())) {
                ordered.add(option);
            }
        }
        return ordered;
    }

    private static String displayLabel(int index) {
        if (index >= 0 && index < 26) {
            return String.valueOf((char) ('A' + index));
        }
        return String.valueOf(index + 1);
    }
}
