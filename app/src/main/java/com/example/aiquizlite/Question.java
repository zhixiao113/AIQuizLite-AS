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
    private final String explanation;
    private final MatchingData matching;
    private final List<String> explanationImages;
    private final String sourceChapter;
    private final String sourceName;

    public Question(
            String id,
            int index,
            String paper,
            int numberInPaper,
            String type,
            String stem,
            List<Option> options,
            List<String> answers,
            String image,
            String explanation
    ) {
        this(id, index, paper, numberInPaper, type, stem, options, answers,
                image, explanation, null, Collections.emptyList());
    }

    public Question(
            String id,
            int index,
            String paper,
            int numberInPaper,
            String type,
            String stem,
            List<Option> options,
            List<String> answers,
            String image,
            String explanation,
            MatchingData matching,
            List<String> explanationImages
    ) {
        this(id, index, paper, numberInPaper, type, stem, options, answers, image,
                explanation, matching, explanationImages, null, null);
    }

    public Question(String id, int index, String paper, int numberInPaper, String type,
                    String stem, List<Option> options, List<String> answers, String image,
                    String explanation, MatchingData matching, List<String> explanationImages,
                    String sourceChapter, String sourceName) {
        this.id = id;
        this.index = index;
        this.paper = paper;
        this.numberInPaper = numberInPaper;
        this.stem = stem;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.answers = Collections.unmodifiableList(new ArrayList<>(answers));
        this.type = normalizeType(type, stem, this.answers);
        this.image = image;
        this.explanation = explanation == null || explanation.trim().isEmpty()
                ? null
                : explanation.trim();
        if ("matching".equals(this.type) && matching == null) {
            throw new IllegalArgumentException("连线题缺少左右项目和标准关系");
        }
        this.matching = matching;
        this.explanationImages = Collections.unmodifiableList(new ArrayList<>(
                explanationImages == null ? Collections.emptyList() : explanationImages));
        this.sourceChapter = sourceChapter;
        this.sourceName = sourceName;
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

    public String getExplanation() {
        return explanation;
    }

    public MatchingData getMatching() { return matching; }

    public List<String> getExplanationImages() { return explanationImages; }
    public String getSourceChapter() { return sourceChapter; }
    public String getSourceName() { return sourceName; }

    public boolean isMatching() { return "matching".equals(type); }

    public boolean isMultiChoice() {
        return "multi".equals(type);
    }

    public boolean isFillBlank() {
        return "fill_blank".equals(type);
    }

    public String typeLabel() {
        switch (type) {
            case "single":
                return "单选题";
            case "multi":
                return "多选题";
            case "judge":
                return "判断题";
            case "fill_blank":
                return "填空题";
            case "matching":
                return "连线题";
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
                case "fill_blank":
                case "fill-blank":
                case "fillblank":
                case "blank":
                case "填空":
                case "填空题":
                    return "fill_blank";
                case "matching":
                case "match":
                case "连线":
                case "连线题":
                    return "matching";
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
