package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class MaterialGenerationPlanTest {
    @Test
    public void chapterTypeMatrixPreservesEveryChapterAndGlobalTypeQuota() {
        MaterialGenerationPlan plan = new MaterialGenerationPlan(
                "网络安全复习",
                "资料.pdf",
                Arrays.asList(
                        new MaterialGenerationPlan.ChapterPlan("基础", "基础内容", 8),
                        new MaterialGenerationPlan.ChapterPlan("策略", "策略内容", 4)
                ),
                quotas(6, 2, 2, 2)
        );

        Map<String, Map<String, Integer>> matrix = plan.allocateTypeMatrix();

        assertEquals(8, sum(matrix.get("基础")));
        assertEquals(4, sum(matrix.get("策略")));
        assertEquals(6, column(matrix, MaterialQuestionRules.SINGLE));
        assertEquals(2, column(matrix, MaterialQuestionRules.MULTI));
        assertEquals(2, column(matrix, MaterialQuestionRules.JUDGE));
        assertEquals(2, column(matrix, MaterialQuestionRules.FILL_BLANK));
    }

    @Test
    public void convertedQuestionBankPlanUsesActualDetectedCounts() throws Exception {
        JSONArray questions = new JSONArray()
                .put(new JSONObject().put("type", "single").put("sourceChapter", "第一章"))
                .put(new JSONObject().put("type", "fill_blank").put("sourceChapter", "第二章"));

        MaterialGenerationPlan plan = MaterialGenerationPlan.fromDetectedQuestions(
                "原题库",
                "原题库.docx",
                Arrays.asList(
                        new MaterialTextRules.Chapter("第一章", "正文一"),
                        new MaterialTextRules.Chapter("第二章", "正文二")
                ),
                questions
        );

        assertEquals(2, plan.getTargetCount());
        assertEquals(1, column(plan.allocateTypeMatrix(), MaterialQuestionRules.SINGLE));
        assertEquals(1, column(plan.allocateTypeMatrix(), MaterialQuestionRules.FILL_BLANK));
    }

    private static Map<String, Integer> quotas(int single, int multi, int judge, int fill) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(MaterialQuestionRules.SINGLE, single);
        result.put(MaterialQuestionRules.MULTI, multi);
        result.put(MaterialQuestionRules.JUDGE, judge);
        result.put(MaterialQuestionRules.FILL_BLANK, fill);
        return result;
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        for (int value : values.values()) {
            total += value;
        }
        return total;
    }

    private static int column(Map<String, Map<String, Integer>> matrix, String type) {
        int total = 0;
        for (Map<String, Integer> row : matrix.values()) {
            total += row.get(type);
        }
        return total;
    }
}
