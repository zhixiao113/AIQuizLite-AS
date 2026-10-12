package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.Assert.*;

public class ExistingQuestionBankImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private JSONObject question(String id, String type, String stem) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("stem", stem)
                .put("options", new JSONArray("[{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"}]"))
                .put("answers", new JSONArray().put("A"));
    }

    @Test public void readyJsonImportsAllExistingTypesWithoutAnAiClient() throws Exception {
        JSONArray items = new JSONArray()
                .put(question("q1", "single", "单选"))
                .put(question("q2", "multi", "多选").put("answers", new JSONArray().put("A").put("B")))
                .put(question("q3", "judge", "判断"))
                .put(question("q4", "fill_blank", "填空").put("options", new JSONArray())
                        .put("answers", new JSONArray().put("答案")))
                .put(new JSONObject().put("id", "q5").put("type", "matching").put("stem", "连线")
                        .put("options", new JSONArray()).put("answers", new JSONArray())
                        .put("matching", new JSONObject()
                                .put("mode", "left_reusable")
                                .put("leftItems", new JSONArray("[{\"id\":\"L1\",\"text\":\"类别\"}]"))
                                .put("rightItems", new JSONArray("[{\"id\":\"R1\",\"text\":\"描述\"}]"))
                                .put("correctPairs", new JSONArray("[{\"leftId\":\"L1\",\"rightId\":\"R1\"}]"))));
        String raw = new JSONObject().put("name", "已有题库").put("questions", items).toString();
        List<Question> questions = QuestionRepository.parsePackagedQuestions(
                QuestionRepository.normalizeImportedJson(raw), "已有题库");
        assertEquals(5, questions.size());
        for (int i = 0; i < items.length(); i++) {
            assertEquals(items.getJSONObject(i).getString("type"), questions.get(i).getType());
        }
        assertEquals("已有题库", QuestionRepository.resolveDisplayNameFromRawJson(raw, "fallback"));
    }

    @Test public void previouslyGeneratedBankIdsAndMetadataStillRoundTrip() throws Exception {
        JSONObject item = question("material_q1", "single", "原来已保存的题目")
                .put("sourceName", "旧资料.docx").put("sourceChapter", "第一章")
                .put("image", "image/stem.png")
                .put("explanationImages", new JSONArray().put("image/answer.png"));
        List<Question> questions = QuestionRepository.parsePackagedQuestions(new JSONArray().put(item), "旧题库");
        JSONObject exported = QuestionRepository.questionsToJson(questions).getJSONObject(0);
        assertEquals("material_q1", exported.getString("id"));
        assertEquals("旧资料.docx", exported.getString("sourceName"));
        assertEquals("第一章", exported.getString("sourceChapter"));
        assertEquals("image/stem.png", exported.getString("image"));
        assertEquals("image/answer.png", exported.getJSONArray("explanationImages").getString(0));
    }

    @Test public void markdownJsonPreparedOutsideTheAppStillImports() throws Exception {
        String raw = "```json\n" + new JSONArray().put(question("q1", "single", "外部题库")) + "\n```";
        assertEquals(1, QuestionRepository.parsePackagedQuestions(
                QuestionRepository.normalizeImportedJson(raw), "外部题库").size());
    }

    @Test public void proseIsRejectedRatherThanBeingSentForGeneration() {
        assertThrows(Exception.class, () -> QuestionRepository.normalizeImportedJson("只有学习资料，没有 JSON 题库"));
    }

    @Test public void optionalLocalDatacomFixturePreserves101QuestionsAnd13Matching() throws Exception {
        // Private source banks must never be committed to public CI fixtures.
        String fixture = System.getenv("AIQUIZ_IMPORT_FIXTURE");
        Assume.assumeTrue(fixture != null && new File(fixture).isFile());
        try (FileInputStream input = new FileInputStream(fixture)) {
            QuestionBankPackageManager.PackagedBank bank =
                    QuestionBankPackageManager.readPackage(input, temp.newFolder());
            List<Question> questions = QuestionRepository.parsePackagedQuestions(bank.questions, bank.displayName);
            assertEquals(101, questions.size());
            assertEquals(13, questions.stream().filter(Question::isMatching).count());
        }
    }
}
