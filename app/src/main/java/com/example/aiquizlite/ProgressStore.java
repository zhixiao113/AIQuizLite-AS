package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ProgressStore {
    private static final String PREF_NAME = "quiz_progress";
    private static final String KEY_WRONG_BOOK = "wrong_book";
    private static final String KEY_DAILY_SESSION = "daily_session";
    private static final String KEY_DAILY_ORDER_REVERSED = "daily_order_reversed";
    private static final String KEY_DAILY_HISTORY = "daily_history";

    private final SharedPreferences preferences;

    public ProgressStore(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public Map<String, Integer> getWrongBook(String questionBankId) {
        try {
            String raw = preferences.getString(namespacedKey(KEY_WRONG_BOOK, questionBankId), "{}");
            JSONObject object = new JSONObject(raw);
            Map<String, Integer> map = new HashMap<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                map.put(key, object.optInt(key, 0));
            }
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public int getWrongCount(String questionBankId) {
        return getWrongBook(questionBankId).size();
    }

    public int getStreak(String questionBankId, String questionId) {
        Integer streak = getWrongBook(questionBankId).get(questionId);
        return streak == null ? 0 : streak;
    }

    public boolean isInWrongBook(String questionBankId, String questionId) {
        return getWrongBook(questionBankId).containsKey(questionId);
    }

    public void recordResult(String questionBankId, String questionId, boolean correct) {
        Map<String, Integer> map = new HashMap<>(getWrongBook(questionBankId));
        if (correct) {
            if (map.containsKey(questionId)) {
                int next = map.get(questionId) + 1;
                if (next >= 3) {
                    map.remove(questionId);
                } else {
                    map.put(questionId, next);
                }
            }
        } else {
            map.put(questionId, 0);
        }
        saveWrongBook(questionBankId, map);
    }

    public void removeWrongQuestion(String questionBankId, String questionId) {
        Map<String, Integer> map = new HashMap<>(getWrongBook(questionBankId));
        if (map.remove(questionId) != null) {
            saveWrongBook(questionBankId, map);
        }
    }

    public DailySession getDailySession(String questionBankId) {
        try {
            String raw = preferences.getString(namespacedKey(KEY_DAILY_SESSION, questionBankId), null);
            if (raw == null || raw.isEmpty()) {
                return null;
            }

            JSONObject object = new JSONObject(raw);
            JSONArray answersArray = object.optJSONArray("selectedAnswers");
            List<String> selectedAnswers = readStringList(answersArray);
            Map<String, AnswerState> answerStates = new HashMap<>();
            JSONArray statesArray = object.optJSONArray("answerStates");
            if (statesArray != null) {
                for (int i = 0; i < statesArray.length(); i++) {
                    JSONObject stateObject = statesArray.getJSONObject(i);
                    String questionId = stateObject.optString("questionId", "");
                    if (questionId.isEmpty()) {
                        continue;
                    }
                    answerStates.put(questionId, new AnswerState(
                            questionId,
                            readStringList(stateObject.optJSONArray("selectedAnswers")),
                            stateObject.optBoolean("answered", false),
                            stateObject.optBoolean("correct", false),
                            stateObject.optBoolean("corrected", false)
                    ));
                }
            }

            return new DailySession(
                    object.optString("questionId", ""),
                    object.optInt("currentIndex", 0),
                    object.optInt("answeredCount", 0),
                    object.optInt("correctCount", 0),
                    object.optBoolean("answerRevealed", false),
                    selectedAnswers,
                    answerStates
            );
        } catch (Exception e) {
            return null;
        }
    }

    public void saveDailySession(String questionBankId, DailySession session) {
        JSONObject object = new JSONObject();
        try {
            object.put("questionId", session.getQuestionId());
            object.put("currentIndex", session.getCurrentIndex());
            object.put("answeredCount", session.getAnsweredCount());
            object.put("correctCount", session.getCorrectCount());
            object.put("answerRevealed", session.isAnswerRevealed());
            object.put("selectedAnswers", toArray(session.getSelectedAnswers()));

            JSONArray statesArray = new JSONArray();
            for (AnswerState state : session.getAnswerStates().values()) {
                JSONObject stateObject = new JSONObject();
                stateObject.put("questionId", state.getQuestionId());
                stateObject.put("selectedAnswers", toArray(state.getSelectedAnswers()));
                stateObject.put("answered", state.isAnswered());
                stateObject.put("correct", state.isCorrect());
                stateObject.put("corrected", state.isCorrected());
                statesArray.put(stateObject);
            }
            object.put("answerStates", statesArray);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save daily session", e);
        }
        preferences.edit()
                .putString(namespacedKey(KEY_DAILY_SESSION, questionBankId), object.toString())
                .apply();
    }

    public void clearDailySession(String questionBankId) {
        preferences.edit().remove(namespacedKey(KEY_DAILY_SESSION, questionBankId)).apply();
    }

    public boolean hasDailySessionInProgress(String questionBankId) {
        return getDailySession(questionBankId) != null;
    }

    public boolean isDailyOrderReversed(String questionBankId) {
        return preferences.getBoolean(namespacedKey(KEY_DAILY_ORDER_REVERSED, questionBankId), false);
    }

    public boolean toggleDailyOrderReversed(String questionBankId) {
        boolean reversed = !isDailyOrderReversed(questionBankId);
        preferences.edit()
                .putBoolean(namespacedKey(KEY_DAILY_ORDER_REVERSED, questionBankId), reversed)
                .apply();
        return reversed;
    }

    public List<DailyPracticeRecord> getDailyPracticeRecords(String questionBankId) {
        try {
            String raw = preferences.getString(namespacedKey(KEY_DAILY_HISTORY, questionBankId), "[]");
            JSONArray array = new JSONArray(raw);
            List<DailyPracticeRecord> records = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                records.add(new DailyPracticeRecord(
                        item.optLong("timestamp", 0L),
                        item.optInt("correctCount", 0),
                        item.optInt("wrongCount", 0)
                ));
            }
            return records;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public void appendDailyPracticeRecord(String questionBankId, DailyPracticeRecord record) {
        List<DailyPracticeRecord> records = new ArrayList<>(getDailyPracticeRecords(questionBankId));
        records.add(record);
        if (records.size() > 50) {
            records = new ArrayList<>(records.subList(records.size() - 50, records.size()));
        }

        JSONArray array = new JSONArray();
        try {
            for (DailyPracticeRecord item : records) {
                JSONObject object = new JSONObject();
                object.put("timestamp", item.getTimestamp());
                object.put("correctCount", item.getCorrectCount());
                object.put("wrongCount", item.getWrongCount());
                array.put(object);
            }
        } catch (Exception e) {
            throw new RuntimeException("保存日常记录失败", e);
        }

        preferences.edit()
                .putString(namespacedKey(KEY_DAILY_HISTORY, questionBankId), array.toString())
                .apply();
    }

    public void clearDailyPracticeRecords(String questionBankId) {
        preferences.edit()
                .remove(namespacedKey(KEY_DAILY_HISTORY, questionBankId))
                .apply();
    }

    private void saveWrongBook(String questionBankId, Map<String, Integer> map) {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            throw new RuntimeException("保存错题本失败", e);
        }
        preferences.edit()
                .putString(namespacedKey(KEY_WRONG_BOOK, questionBankId), object.toString())
                .apply();
    }

    private List<String> readStringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length(); i++) {
            values.add(array.optString(i));
        }
        return values;
    }

    private JSONArray toArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private String namespacedKey(String baseKey, String questionBankId) {
        String safeQuestionBankId = questionBankId == null || questionBankId.isEmpty()
                ? QuestionRepository.getDefaultQuestionBank().getId()
                : questionBankId;
        return baseKey + "_" + safeQuestionBankId;
    }

    public static class DailySession {
        private final String questionId;
        private final int currentIndex;
        private final int answeredCount;
        private final int correctCount;
        private final boolean answerRevealed;
        private final List<String> selectedAnswers;
        private final Map<String, AnswerState> answerStates;

        public DailySession(
                String questionId,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                List<String> selectedAnswers
        ) {
            this(questionId, currentIndex, answeredCount, correctCount, answerRevealed, selectedAnswers, Collections.emptyMap());
        }

        public DailySession(
                String questionId,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                List<String> selectedAnswers,
                Map<String, AnswerState> answerStates
        ) {
            this.questionId = questionId;
            this.currentIndex = currentIndex;
            this.answeredCount = answeredCount;
            this.correctCount = correctCount;
            this.answerRevealed = answerRevealed;
            this.selectedAnswers = Collections.unmodifiableList(new ArrayList<>(selectedAnswers));
            this.answerStates = Collections.unmodifiableMap(new HashMap<>(answerStates));
        }

        public String getQuestionId() {
            return questionId;
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public int getAnsweredCount() {
            return answeredCount;
        }

        public int getCorrectCount() {
            return correctCount;
        }

        public boolean isAnswerRevealed() {
            return answerRevealed;
        }

        public List<String> getSelectedAnswers() {
            return selectedAnswers;
        }

        public Map<String, AnswerState> getAnswerStates() {
            return answerStates;
        }
    }

    public static class AnswerState {
        private final String questionId;
        private final List<String> selectedAnswers;
        private final boolean answered;
        private final boolean correct;
        private final boolean corrected;

        public AnswerState(
                String questionId,
                List<String> selectedAnswers,
                boolean answered,
                boolean correct,
                boolean corrected
        ) {
            this.questionId = questionId;
            this.selectedAnswers = Collections.unmodifiableList(new ArrayList<>(selectedAnswers));
            this.answered = answered;
            this.correct = correct;
            this.corrected = corrected;
        }

        public String getQuestionId() {
            return questionId;
        }

        public List<String> getSelectedAnswers() {
            return selectedAnswers;
        }

        public boolean isAnswered() {
            return answered;
        }

        public boolean isCorrect() {
            return correct;
        }

        public boolean isCorrected() {
            return corrected;
        }
    }

    public static class DailyPracticeRecord {
        private final long timestamp;
        private final int correctCount;
        private final int wrongCount;

        public DailyPracticeRecord(long timestamp, int correctCount, int wrongCount) {
            this.timestamp = timestamp;
            this.correctCount = correctCount;
            this.wrongCount = wrongCount;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public int getCorrectCount() {
            return correctCount;
        }

        public int getWrongCount() {
            return wrongCount;
        }

        public int getTotalCount() {
            return correctCount + wrongCount;
        }

        public int getAccuracyPercent() {
            int totalCount = getTotalCount();
            if (totalCount == 0) {
                return 0;
            }
            return Math.round(correctCount * 100f / totalCount);
        }
    }
}
