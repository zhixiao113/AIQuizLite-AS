package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Question {
    private final String id;
    private final int index;
    private final String paper;
    private final int numberInPaper;
    private final String type;
    private final String stem;
    private final List<Option> options;
    private final List<String> answers;
    private final String image;

    public Question(
            String id,
            int index,
            String paper,
            int numberInPaper,
            String type,
            String stem,
            List<Option> options,
            List<String> answers,
            String image
    ) {
        this.id = id;
        this.index = index;
        this.paper = paper;
        this.numberInPaper = numberInPaper;
        this.stem = stem;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.answers = Collections.unmodifiableList(new ArrayList<>(answers));
        this.type = normalizeType(type, stem, this.answers);
        this.image = image;
    }

    public String getId() {
        return id;
    }

    public int getIndex() {
        return index;
    }

    public String getPaper() {
        return paper;
    }

    public int getNumberInPaper() {
        return numberInPaper;
    }

    public String getType() {
        return type;
    }

    public String getStem() {
        return stem;
    }

    public List<Option> getOptions() {
        return options;
    }

    public List<String> getAnswers() {
        return answers;
    }

    public String getImage() {
        return image;
    }

    public boolean isMultiChoice() {
        return "multi".equals(type);
    }

    public String typeLabel() {
        switch (type) {
            case "single":
                return "单选题";
            case "multi":
                return "多选题";
            case "judge":
                return "判断题";
            default:
                return type;
        }
    }

    private static String normalizeType(String rawType, String stem, List<String> answers) {
        if (rawType != null) {
            String normalized = rawType.trim().toLowerCase();
            switch (normalized) {
                case "multi":
                case "multiple":
                case "multiple_choice":
                case "multiple-choice":
                    return "multi";
                case "single":
                case "single_choice":
                case "single-choice":
                    return "single";
                case "judge":
                case "boolean":
                case "true_false":
                case "true-false":
                    return "judge";
                default:
                    break;
            }
        }

        if (answers != null && answers.size() > 1) {
            return "multi";
        }
        if (stem != null && (stem.contains("多选") || stem.contains("多项"))) {
            return "multi";
        }
        return rawType == null ? "single" : rawType.trim().toLowerCase();
    }

    public static class Option {
        private final String key;
        private final String text;

        public Option(String key, String text) {
            this.key = key;
            this.text = text;
        }

        public String getKey() {
            return key;
        }

        public String getText() {
            return text;
        }

        public String displayText() {
            return key + ". " + text;
        }
    }
}
