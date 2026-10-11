package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MaterialGenerationPlan {
    private static final String[] TYPE_ORDER = {
            MaterialQuestionRules.SINGLE,
            MaterialQuestionRules.MULTI,
            MaterialQuestionRules.JUDGE,
            MaterialQuestionRules.FILL_BLANK,
            MaterialQuestionRules.MATCHING
    };

    private final String bankName;
    private final String sourceName;
    private final List<ChapterPlan> chapters;
    private final Map<String, Integer> typeQuotas;
    private final Map<String, Map<String, Integer>> explicitTypeMatrix;

    public MaterialGenerationPlan(
            String bankName,
            String sourceName,
            List<ChapterPlan> chapters,
            Map<String, Integer> typeQuotas
    ) {
        this(bankName, sourceName, chapters, typeQuotas, null);
    }

    public MaterialGenerationPlan(
            String bankName,
            String sourceName,
            List<ChapterPlan> chapters,
            Map<String, Integer> typeQuotas,
            Map<String, Map<String, Integer>> explicitTypeMatrix
    ) {
        this.bankName = safeName(bankName, "资料生成题库");
        this.sourceName = safeName(sourceName, "学习资料");
        this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
        this.typeQuotas = Collections.unmodifiableMap(normalizeTypeQuotas(typeQuotas));
        this.explicitTypeMatrix = normalizeExplicitMatrix(explicitTypeMatrix);
        if (this.chapters.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个出题章节");
        }
        if (getTargetCount() <= 0) {
            throw new IllegalArgumentException("总题量必须大于 0");
        }
        int chapterTotal = 0;
        for (ChapterPlan chapter : this.chapters) {
            chapterTotal += chapter.getQuestionCount();
        }
        if (chapterTotal != getTargetCount()) {
            throw new IllegalArgumentException("章节题量与题型题量合计不一致");
        }
        validateExplicitMatrix();
    }

    public static MaterialGenerationPlan recommended(
            String bankName,
            String sourceName,
            List<MaterialTextRules.Chapter> detectedChapters
    ) {
        List<MaterialTextRules.Chapter> usable = new ArrayList<>();
        for (MaterialTextRules.Chapter chapter : detectedChapters) {
            if (chapter != null && !chapter.getContent().isEmpty()) {
                usable.add(chapter);
            }
        }
        int characters = MaterialTextRules.countCharacters(usable);
        int target = MaterialTextRules.recommendQuestionCount(characters);
        List<ChapterPlan> chapterPlans = allocateChapterCounts(usable, target);
        return new MaterialGenerationPlan(
                bankName,
                sourceName,
                chapterPlans,
                recommendTypes(target)
        );
    }

    public static MaterialGenerationPlan fromDetectedQuestions(
            String bankName,
            String sourceName,
            List<MaterialTextRules.Chapter> sourceChapters,
            JSONArray questions
    ) throws JSONException {
        Map<String, String> sourceText = new LinkedHashMap<>();
        if (sourceChapters != null) {
            for (MaterialTextRules.Chapter chapter : sourceChapters) {
                if (chapter != null) {
                    sourceText.put(chapter.getTitle(), chapter.getContent());
                }
            }
        }
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        Map<String, Integer> totals = emptyTypeCounts();
        JSONArray safeQuestions = questions == null ? new JSONArray() : questions;
        for (int i = 0; i < safeQuestions.length(); i++) {
            JSONObject question = safeQuestions.optJSONObject(i);
            if (question == null) {
                continue;
            }
            String type = MaterialQuestionRules.normalizeType(question.optString("type", ""));
            if (type == null) {
                continue;
            }
            String chapter = question.optString("sourceChapter", "").trim();
            if (chapter.isEmpty()) {
                chapter = "全文";
                question.put("sourceChapter", chapter);
            }
            matrix.computeIfAbsent(chapter, key -> emptyTypeCounts());
            matrix.get(chapter).put(type, matrix.get(chapter).get(type) + 1);
            totals.put(type, totals.get(type) + 1);
        }
        List<ChapterPlan> chapterPlans = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : matrix.entrySet()) {
            chapterPlans.add(new ChapterPlan(
                    entry.getKey(),
                    sourceText.getOrDefault(entry.getKey(), ""),
                    sum(entry.getValue())
            ));
        }
        if (chapterPlans.isEmpty()) {
            throw new IllegalArgumentException("AI 没有识别出可用题目");
        }
        return new MaterialGenerationPlan(
                bankName,
                sourceName,
                chapterPlans,
                totals,
                matrix
        );
    }

    public Map<String, Map<String, Integer>> allocateTypeMatrix() {
        if (explicitTypeMatrix != null) {
            return explicitTypeMatrix;
        }
        Map<String, Integer> remaining = new LinkedHashMap<>(typeQuotas);
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        int remainingTotal = getTargetCount();
        for (int chapterIndex = 0; chapterIndex < chapters.size(); chapterIndex++) {
            ChapterPlan chapter = chapters.get(chapterIndex);
            int target = chapter.getQuestionCount();
            Map<String, Integer> row = emptyTypeCounts();
            if (chapterIndex == chapters.size() - 1) {
                for (String type : TYPE_ORDER) {
                    row.put(type, remaining.get(type));
                }
            } else {
                int allocated = 0;
                for (String type : TYPE_ORDER) {
                    int value = remainingTotal == 0
                            ? 0
                            : (target * remaining.get(type)) / remainingTotal;
                    value = Math.min(value, remaining.get(type));
                    row.put(type, value);
                    allocated += value;
                }
                while (allocated < target) {
                    String bestType = null;
                    int bestAvailable = -1;
                    for (String type : TYPE_ORDER) {
                        int available = remaining.get(type) - row.get(type);
                        if (available > bestAvailable) {
                            bestAvailable = available;
                            bestType = type;
                        }
                    }
                    if (bestType == null || bestAvailable <= 0) {
                        throw new IllegalStateException("无法完成题型分配");
                    }
                    row.put(bestType, row.get(bestType) + 1);
                    allocated++;
                }
            }
            for (String type : TYPE_ORDER) {
                remaining.put(type, remaining.get(type) - row.get(type));
            }
            remainingTotal -= target;
            result.put(chapter.getTitle(), Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableMap(result);
    }

    public String getBankName() {
        return bankName;
    }

    public String getSourceName() {
        return sourceName;
    }

    public List<ChapterPlan> getChapters() {
        return chapters;
    }

    public Map<String, Integer> getTypeQuotas() {
        return typeQuotas;
    }

    public int getTargetCount() {
        int total = 0;
        for (int value : typeQuotas.values()) {
            total += value;
        }
        return total;
    }

    public int getSelectedCharacterCount() {
        int total = 0;
        for (ChapterPlan chapter : chapters) {
            total += chapter.getText().length();
        }
        return total;
    }

    public ChapterPlan findChapter(String title) {
        for (ChapterPlan chapter : chapters) {
            if (chapter.getTitle().equals(title)) {
                return chapter;
            }
        }
        return null;
    }

    private void validateExplicitMatrix() {
        if (explicitTypeMatrix == null) {
            return;
        }
        Map<String, Integer> columns = emptyTypeCounts();
        for (ChapterPlan chapter : chapters) {
            Map<String, Integer> row = explicitTypeMatrix.get(chapter.getTitle());
            if (row == null) {
                throw new IllegalArgumentException("自定义题型分配缺少章节：" + chapter.getTitle());
            }
            int rowTotal = 0;
            for (String type : TYPE_ORDER) {
                int value = row.get(type);
                rowTotal += value;
                columns.put(type, columns.get(type) + value);
            }
            if (rowTotal != chapter.getQuestionCount()) {
                throw new IllegalArgumentException("章节题型分配与章节题量不一致");
            }
        }
        if (!columns.equals(typeQuotas)) {
            throw new IllegalArgumentException("章节题型分配与题型总量不一致");
        }
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        if (values != null) {
            for (Integer value : values.values()) {
                total += value == null ? 0 : Math.max(0, value);
            }
        }
        return total;
    }

    private static Map<String, Map<String, Integer>> normalizeExplicitMatrix(
            Map<String, Map<String, Integer>> raw
    ) {
        if (raw == null) {
            return null;
        }
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : raw.entrySet()) {
            result.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(normalizeTypeQuotas(entry.getValue()))
            );
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<ChapterPlan> allocateChapterCounts(
            List<MaterialTextRules.Chapter> chapters,
            int target
    ) {
        List<ChapterPlan> result = new ArrayList<>();
        int characters = Math.max(1, MaterialTextRules.countCharacters(chapters));
        int remaining = target;
        for (int i = 0; i < chapters.size(); i++) {
            MaterialTextRules.Chapter chapter = chapters.get(i);
            int count;
            if (remaining <= 0) {
                count = 0;
            } else if (i == chapters.size() - 1) {
                count = remaining;
            } else {
                count = Math.max(1, (target * chapter.getCharacterCount()) / characters);
                int laterChapters = chapters.size() - i - 1;
                int reserveForLater = Math.min(laterChapters, Math.max(0, remaining - 1));
                count = Math.min(count, remaining - reserveForLater);
            }
            remaining -= count;
            result.add(new ChapterPlan(chapter.getTitle(), chapter.getContent(), count));
        }
        return result;
    }

    public static Map<String, Integer> recommendTypes(int total) {
        Map<String, Integer> result = emptyTypeCounts();
        int single = total / 2;
        int multi = total / 5;
        int judge = total / 5;
        int fill = total - single - multi - judge;
        result.put(MaterialQuestionRules.SINGLE, single);
        result.put(MaterialQuestionRules.MULTI, multi);
        result.put(MaterialQuestionRules.JUDGE, judge);
        result.put(MaterialQuestionRules.FILL_BLANK, fill);
        return result;
    }

    private static Map<String, Integer> normalizeTypeQuotas(Map<String, Integer> raw) {
        Map<String, Integer> result = emptyTypeCounts();
        if (raw != null) {
            for (Map.Entry<String, Integer> entry : raw.entrySet()) {
                String type = MaterialQuestionRules.normalizeType(entry.getKey());
                if (type != null) {
                    result.put(type, Math.max(0, entry.getValue() == null ? 0 : entry.getValue()));
                }
            }
        }
        return result;
    }

    private static Map<String, Integer> emptyTypeCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String type : TYPE_ORDER) {
            result.put(type, 0);
        }
        return result;
    }

    private static String safeName(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    public static final class ChapterPlan {
        private final String title;
        private final String text;
        private final int questionCount;

        public ChapterPlan(String title, String text, int questionCount) {
            this.title = safeName(title, "全文");
            this.text = MaterialTextRules.normalizeText(text);
            this.questionCount = Math.max(0, questionCount);
        }

        public String getTitle() {
            return title;
        }

        public String getText() {
            return text;
        }

        public int getQuestionCount() {
            return questionCount;
        }
    }
}
