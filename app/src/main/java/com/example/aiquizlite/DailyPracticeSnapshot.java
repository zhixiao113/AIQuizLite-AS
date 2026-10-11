package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Frozen question text, answers and submitted results, independent of later bank edits. */
final class DailyPracticeSnapshot {
    final String id;
    final String bankId;
    final String bankName;
    final long timestamp;
    final List<Question> questions;
    final Map<String, ProgressStore.AnswerState> answers;

    DailyPracticeSnapshot(String bankId, String bankName, long timestamp,
                          List<Question> questions, Map<String, ProgressStore.AnswerState> answers) {
        this(UUID.randomUUID().toString(), bankId, bankName, timestamp, questions, answers);
    }

    private DailyPracticeSnapshot(String id, String bankId, String bankName, long timestamp,
                                  List<Question> questions, Map<String, ProgressStore.AnswerState> answers) {
        this.id = id;
        this.bankId = bankId;
        this.bankName = bankName;
        this.timestamp = timestamp;
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
        this.answers = Collections.unmodifiableMap(new HashMap<>(answers));
    }

    String toJson() throws Exception {
        JSONObject root = new JSONObject().put("version", 2).put("id", id)
                .put("bankId", bankId).put("bankName", bankName).put("timestamp", timestamp);
        JSONArray items = new JSONArray();
        for (Question question : questions) {
            JSONObject item = new JSONObject().put("id", question.getId())
                    .put("index", question.getIndex()).put("paper", question.getPaper())
                    .put("numberInPaper", question.getNumberInPaper()).put("type", question.getType())
                    .put("stem", question.getStem()).put("image", question.getImage())
                    .put("explanation", question.getExplanation())
                    .put("sourceChapter", question.getSourceChapter())
                    .put("sourceName", question.getSourceName());
            JSONArray options = new JSONArray();
            for (Question.Option option : question.getOptions()) {
                options.put(new JSONObject().put("key", option.getKey()).put("text", option.getText()));
            }
            item.put("options", options).put("answers", new JSONArray(question.getAnswers()));
            if (question.isMatching()) item.put("matching", question.getMatching().toJson());
            item.put("explanationImages", new JSONArray(question.getExplanationImages()));
            ProgressStore.AnswerState state = answers.get(question.getId());
            if (state != null && state.isAnswered()) {
                item.put("result", new JSONObject().put("selected", new JSONArray(state.getSelectedAnswers()))
                        .put("selectedPairs", MatchingData.pairsToJson(state.getSelectedPairs()))
                        .put("correct", state.isCorrect()).put("corrected", state.isCorrected()));
            }
            items.put(item);
        }
        return root.put("questions", items).toString();
    }

    static DailyPracticeSnapshot fromJson(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        int version = root.getInt("version");
        if (version != 1 && version != 2) throw new IllegalArgumentException("不支持的历史记录版本");
        List<Question> questions = new ArrayList<>();
        Map<String, ProgressStore.AnswerState> answers = new HashMap<>();
        JSONArray items = root.getJSONArray("questions");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            List<Question.Option> options = new ArrayList<>();
            JSONArray optionArray = item.getJSONArray("options");
            for (int j = 0; j < optionArray.length(); j++) {
                JSONObject option = optionArray.getJSONObject(j);
                options.add(new Question.Option(option.getString("key"), option.getString("text")));
            }
            String id = item.getString("id");
            questions.add(new Question(id, item.getInt("index"), item.getString("paper"),
                    item.getInt("numberInPaper"), item.getString("type"), item.getString("stem"),
                    options, strings(item.getJSONArray("answers")),
                    item.isNull("image") ? null : item.optString("image", null),
                    item.isNull("explanation") ? null : item.optString("explanation", null),
                    item.optJSONObject("matching") == null ? null
                            : MatchingData.fromJson(item.getJSONObject("matching")),
                    strings(item.optJSONArray("explanationImages")),
                    item.isNull("sourceChapter") ? null : item.optString("sourceChapter", null),
                    item.isNull("sourceName") ? null : item.optString("sourceName", null)));
            JSONObject result = item.optJSONObject("result");
            if (result != null) answers.put(id, new ProgressStore.AnswerState(id,
                    strings(result.getJSONArray("selected")), true, result.getBoolean("correct"),
                    result.optBoolean("corrected", false),
                    MatchingData.pairsFromJson(result.optJSONArray("selectedPairs"))));
        }
        return new DailyPracticeSnapshot(root.getString("id"), root.getString("bankId"),
                root.getString("bankName"), root.getLong("timestamp"), questions, answers);
    }

    private static List<String> strings(JSONArray array) throws Exception {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }
}
