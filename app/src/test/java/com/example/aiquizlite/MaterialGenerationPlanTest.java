package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;

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
