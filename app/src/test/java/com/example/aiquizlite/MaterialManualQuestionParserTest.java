package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MaterialManualQuestionParserTest {
    @Test
    public void parsesAFullPastedQuestionAndUsesTheCorrectAnswerSection() throws Exception {
        String pasted = "8. (填空题)如果内部员工不能正常连网，可使用什么查看命令？\n"
                + "我的答案：\n(1)\ndisplay session table\n"
                + "正确答案：\n(1) display firewall session table verbose\n"
                + "答案解析：用于查看防火墙会话。";

        JSONArray result = MaterialManualQuestionParser.parse(pasted);
        JSONObject question = result.getJSONObject(0);

        assertEquals(1, result.length());
        assertEquals(MaterialQuestionRules.FILL_BLANK, question.getString("type"));
        assertEquals("display firewall session table verbose", question.getJSONArray("answers").getString(0));
        assertEquals("用于查看防火墙会话。", question.getString("explanation"));
    }
}
