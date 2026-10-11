package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class QuestionMetadataTest {
    private JSONObject item() throws Exception {
        return new JSONObject().put("id", "q1").put("stem", "题干")
                .put("type", "fill_blank").put("answers", new JSONArray().put("答案"))
                .put("sourceChapter", "第三章 BGP").put("sourceName", "原始试卷.docx");
    }

    @Test public void importExportAndHistoryKeepSourceMetadata() throws Exception {
        List<Question> questions = QuestionRepository.parsePackagedQuestions(
                new JSONArray().put(item()), "试卷");
        JSONObject exported = QuestionRepository.questionsToJson(questions).getJSONObject(0);
        assertEquals("第三章 BGP", exported.getString("sourceChapter"));
        assertEquals("原始试卷.docx", exported.getString("sourceName"));
        DailyPracticeSnapshot snapshot = DailyPracticeSnapshot.fromJson(
                new DailyPracticeSnapshot("b", "题库", 1, questions, Collections.emptyMap()).toJson());
        JSONObject history = QuestionRepository.questionsToJson(snapshot.questions).getJSONObject(0);
        assertEquals(exported.getString("sourceChapter"), history.getString("sourceChapter"));
        assertEquals(exported.getString("sourceName"), history.getString("sourceName"));
    }

    @Test public void duplicateIdsAreRejectedBeforeImport() throws Exception {
        try {
            QuestionRepository.parsePackagedQuestions(new JSONArray().put(item()).put(item()), "卷");
            fail("重复题目 ID 不应导入");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ID"));
        }
    }

    @Test public void blankIdsAreRejectedButMissingIdsStillGenerateDefaults() throws Exception {
        JSONObject missing = item();
        missing.remove("id");
        assertEquals("q1", QuestionRepository.parsePackagedQuestions(
                new JSONArray().put(missing), "卷").get(0).getId());
        for (Object invalid : new Object[]{" ", JSONObject.NULL}) {
            try {
                QuestionRepository.parsePackagedQuestions(
                        new JSONArray().put(item().put("id", invalid)), "卷");
                fail("空 ID 不应导入");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("ID"));
            }
        }
    }
}
