package com.example.aiquizlite;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DailyPracticeSnapshotTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Question question(String id, String type) {
        return new Question(id, 9, "来源", 7, type, "当时的题干",
                Arrays.asList(new Question.Option("A", "当时选项"), new Question.Option("B", "另一选项")),
                Collections.singletonList(type.equals("fill_blank") ? "快照答案" : "A"), null, "当时解析");
    }

    @Test public void roundTripPreservesOrderTextSelectionsAndJudgement() throws Exception {
        Map<String, ProgressStore.AnswerState> states = new HashMap<>();
        states.put("b", new ProgressStore.AnswerState("b", Collections.singletonList("B"), true, false, false));
        DailyPracticeSnapshot source = new DailyPracticeSnapshot("bank", "题库", 12,
                Arrays.asList(question("b", "single"), question("a", "multi")), states);
        states.clear();
        DailyPracticeSnapshot restored = DailyPracticeSnapshot.fromJson(source.toJson());
        assertEquals(source.id, restored.id);
        assertEquals("b", restored.questions.get(0).getId());
        assertEquals("当时的题干", restored.questions.get(0).getStem());
        assertEquals("当时选项", restored.questions.get(0).getOptions().get(0).getText());
        assertEquals("当时解析", restored.questions.get(0).getExplanation());
        assertEquals(Collections.singletonList("B"), restored.answers.get("b").getSelectedAnswers());
        assertFalse(restored.answers.get("b").isCorrect());
        assertNull(restored.answers.get("a"));
    }

    @Test public void correctedAndFillBlankAnswersKeepTheirOriginalResult() throws Exception {
        Map<String, ProgressStore.AnswerState> states = new HashMap<>();
        states.put("fill", new ProgressStore.AnswerState("fill", Collections.singletonList("用户修正答案"), true, true, true));
        DailyPracticeSnapshot restored = DailyPracticeSnapshot.fromJson(new DailyPracticeSnapshot("b", "n", 1,
                Collections.singletonList(question("fill", "fill_blank")), states).toJson());
        assertTrue(restored.questions.get(0).isFillBlank());
        assertTrue(restored.answers.get("fill").isCorrected());
        assertTrue(restored.answers.get("fill").isCorrect());
        assertEquals("用户修正答案", restored.answers.get("fill").getSelectedAnswers().get(0));
    }

    @Test public void unsubmittedDraftIsNotTreatedAsAnAnsweredQuestion() throws Exception {
        Map<String, ProgressStore.AnswerState> states = new HashMap<>();
        states.put("q", new ProgressStore.AnswerState("q", Collections.singletonList("A"), false, true, false));
        DailyPracticeSnapshot restored = DailyPracticeSnapshot.fromJson(new DailyPracticeSnapshot("b", "n", 1,
                Collections.singletonList(question("q", "single")), states).toJson());
        assertTrue(restored.answers.isEmpty());
    }

    @Test public void savedFilesLoadLazilyAndCanBeCleared() throws Exception {
        DailyPracticeHistoryStore store = new DailyPracticeHistoryStore(temporary.newFolder());
        DailyPracticeSnapshot snapshot = new DailyPracticeSnapshot("b", "n", 1,
                Collections.singletonList(question("q", "single")), Collections.emptyMap());
        store.save(snapshot);
        assertEquals(snapshot.id, store.load(snapshot.id).id);
        store.delete(snapshot.id);
        assertNull(store.load(snapshot.id));
        store.save(snapshot);
        store.clear();
        assertNull(store.load(snapshot.id));
    }

    @Test public void invalidOrCorruptRecordsDoNotCrashTheHistoryList() throws Exception {
        java.io.File directory = temporary.newFolder();
        DailyPracticeHistoryStore store = new DailyPracticeHistoryStore(directory);
        String id = "00000000-0000-0000-0000-000000000000";
        Files.write(new java.io.File(directory, id + ".json").toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertNull(store.load(id));
        assertNull(store.load("../../private"));
        assertNull(store.load(null));
    }

    @Test public void legacySummaryHasNoFabricatedDetails() {
        ProgressStore.DailyPracticeRecord record = new ProgressStore.DailyPracticeRecord(1, 3, 2);
        assertEquals("", record.getDetailId());
        assertEquals(60, record.getAccuracyPercent());
    }
}
