package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class SourceQuestionAuditTest {
    @Test public void detectsOriginalNumbersWithoutTreatingOptionsAsQuestions() throws Exception {
        String source = "88.（填空题）内容\nA. 选项\n89.（连线题）内容\n90.（连线题）内容";
        JSONArray accepted = new JSONArray().put(new JSONObject().put("numberInPaper", 88))
                .put(new JSONObject().put("numberInPaper", 90));
        assertEquals(Arrays.asList(88, 89, 90), SourceQuestionAudit.sourceNumbers(source));
        assertEquals(Arrays.asList(89), SourceQuestionAudit.missingNumbers(source, accepted));
        accepted.put(new JSONObject().put("numberInPaper", 90));
        accepted.put(new JSONObject().put("numberInPaper", 91));
        assertEquals(Arrays.asList(90), SourceQuestionAudit.duplicateNumbers(accepted));
        assertEquals(Arrays.asList(91), SourceQuestionAudit.unexpectedNumbers(source, accepted));
    }
}
