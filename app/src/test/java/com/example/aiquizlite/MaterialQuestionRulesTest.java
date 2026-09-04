package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class MaterialQuestionRulesTest {
    @Test
    public void strictValidationKeepsOnlyValidUniqueQuestionsAndReportsTheExactGap() throws Exception {
        JSONArray candidates = new JSONArray()
                .put(choice("single", "防火墙接口加入安全区域后才能转发流量。", "A"))
                .put(choice("single", "防火墙接口加入安全区域后，才能转发流量！", "A"))
                .put(choice("single", "无效答案不会被接收。", "C"))
                .put(fill("查看会话表的命令是______。", "display firewall session table verbose"));
        Map<String, Integer> quotas = new LinkedHashMap<>();
        quotas.put("single", 2);
        quotas.put("fill_blank", 1);

        MaterialQuestionRules.ValidationResult result =
                MaterialQuestionRules.validate(candidates, quotas, "测试资料");

        assertEquals(2, result.getAccepted().length());
        assertEquals(1, result.getDuplicateCount());
        assertEquals(1, result.getInvalidCount());
        assertEquals(1, result.getMissingByType().get("single").intValue());
        assertEquals(0, result.getMissingByType().get("fill_blank").intValue());
    }

    @Test
    public void detectedQuestionValidationDoesNotDropValidTypesBecauseOfPresetQuotas() throws Exception {
        JSONArray candidates = new JSONArray()
                .put(choice("single", "第一道单选题", "A"))
                .put(choice("judge", "一道判断题", "A"))
                .put(fill("一道填空题", "答案"));

        MaterialQuestionRules.ValidationResult result =
                MaterialQuestionRules.validateDetected(candidates, "原题库");

        assertEquals(3, result.getAccepted().length());
        assertEquals(0, result.getInvalidCount());
        assertEquals(0, result.getOverflowCount());
    }

    @Test
    public void detectedFillQuestionKeepsMultipleExplicitAcceptedAnswers() throws Exception {
        JSONObject question = fill("设备所在安全区域是______。", "Local")
                .put("answers", new JSONArray().put("Local").put("Local区域"));

        MaterialQuestionRules.ValidationResult result =
                MaterialQuestionRules.validateDetected(new JSONArray().put(question), "原题库");

        assertEquals(1, result.getAccepted().length());
        assertEquals(2, result.getAccepted().getJSONObject(0).getJSONArray("answers").length());
    }

    private static JSONObject choice(String type, String stem, String answer) throws Exception {
        return new JSONObject()
                .put("type", type)
                .put("stem", stem)
                .put("options", new JSONArray()
                        .put(new JSONObject().put("key", "A").put("text", "对"))
                        .put(new JSONObject().put("key", "B").put("text", "错")))
                .put("answers", new JSONArray().put(answer));
    }

    private static JSONObject fill(String stem, String answer) throws Exception {
        return new JSONObject()
                .put("type", "fill_blank")
                .put("stem", stem)
                .put("options", new JSONArray())
                .put("answers", new JSONArray().put(answer));
    }
}
