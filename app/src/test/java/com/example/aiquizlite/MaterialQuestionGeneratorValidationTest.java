package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class MaterialQuestionGeneratorValidationTest {
    @Test
    public void finalValidationRejectsDuplicatesAcrossDifferentChaptersAndKeepsTheGapVisible() throws Exception {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put(MaterialQuestionRules.SINGLE, 2);
        MaterialGenerationPlan plan = new MaterialGenerationPlan(
                "测试题库",
                "资料.txt",
                Arrays.asList(
                        new MaterialGenerationPlan.ChapterPlan("第一章", "正文一", 1),
                        new MaterialGenerationPlan.ChapterPlan("第二章", "正文二", 1)
                ),
                types
        );
        JSONArray candidates = new JSONArray()
                .put(choice("第一章", "接口必须加入安全区域。"))
                .put(choice("第二章", "接口必须加入安全区域！"));

        MaterialQuestionGenerator.PlanValidationResult result =
                MaterialQuestionGenerator.validatePlan(candidates, plan);

        assertEquals(1, result.getAccepted().length());
        assertEquals(1, result.getDuplicateCount());
        assertEquals(1, result.getMissingCount());
        assertFalse(result.isComplete());
    }

    private static JSONObject choice(String chapter, String stem) throws Exception {
        return new JSONObject()
                .put("sourceChapter", chapter)
                .put("type", "single")
                .put("stem", stem)
                .put("options", new JSONArray()
                        .put(new JSONObject().put("key", "A").put("text", "正确"))
                        .put(new JSONObject().put("key", "B").put("text", "错误")))
                .put("answers", new JSONArray().put("A"));
    }
}
