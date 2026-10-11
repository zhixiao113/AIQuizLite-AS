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
import java.util.HashSet;
import java.util.Set;

public class ProgressStore {
    private static final String PREF_NAME = "quiz_progress";
    private static final String KEY_WRONG_BOOK = "wrong_book";
    private static final String KEY_WRONG_SESSION = "wrong_session";
    private static final String KEY_DAILY_SESSION = "daily_session";
    private static final String KEY_DAILY_ORDER_REVERSED = "daily_order_reversed";
    private static final String KEY_DAILY_HISTORY = "daily_history";
    private static final String KEY_FAVORITES = "favorites";

    private final SharedPreferences preferences;
    private final DailyPracticeHistoryStore dailyHistoryStore;

    public ProgressStore(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        dailyHistoryStore = new DailyPracticeHistoryStore(context);
    }

    ProgressStore(SharedPreferences preferences, DailyPracticeHistoryStore dailyHistoryStore) {
        this.preferences = preferences;
        this.dailyHistoryStore = dailyHistoryStore;
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
        recordResult(questionBankId, questionId, correct, true, 3);
    }

    public void recordResult(
            String questionBankId,
            String questionId,
            boolean correct,
            boolean autoAddWrongQuestion,
            int removalThreshold
    ) {
        Map<String, Integer> map = new HashMap<>(getWrongBook(questionBankId));
        if (correct) {
            if (map.containsKey(questionId)) {
                int normalizedThreshold = GeneralSettingsRules
                        .normalizeWrongRemovalThreshold(removalThreshold);
                int next = map.get(questionId) + 1;
                if (normalizedThreshold > 0 && next >= normalizedThreshold) {
                    map.remove(questionId);
                } else if (normalizedThreshold > 0) {
                    map.put(questionId, next);
                }
            }
        } else if (autoAddWrongQuestion || map.containsKey(questionId)) {
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

    public Set<String> getFavorites(String questionBankId) {
        try {
            String raw = preferences.getString(namespacedKey(KEY_FAVORITES, questionBankId), "[]");
            JSONArray array = new JSONArray(raw);
            Set<String> favorites = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                String questionId = array.optString(i, "");
                if (!questionId.isEmpty()) {
                    favorites.add(questionId);
                }
            }
            return favorites;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    public int getFavoriteCount(String questionBankId) {
        return getFavorites(questionBankId).size();
    }

    public boolean isFavorite(String questionBankId, String questionId) {
        return getFavorites(questionBankId).contains(questionId);
    }

    public boolean toggleFavorite(String questionBankId, String questionId) {
        Set<String> favorites = new HashSet<>(getFavorites(questionBankId));
        boolean favorite;
        if (favorites.contains(questionId)) {
            favorites.remove(questionId);
            favorite = false;
        } else {
            favorites.add(questionId);
            favorite = true;
        }
        saveFavorites(questionBankId, favorites);
        return favorite;
    }

    public void removeQuestionData(String questionBankId, String questionId) {
        clearTypeSessions(questionBankId);
        removeWrongQuestion(questionBankId, questionId);
        Set<String> favorites = new HashSet<>(getFavorites(questionBankId));
        if (favorites.remove(questionId)) {
            saveFavorites(questionBankId, favorites);
        }
        DailySession session = getDailySession(questionBankId);
        if (session != null && (questionId.equals(session.getQuestionId())
                || session.getAnswerStates().containsKey(questionId)
                || session.getDraftAnswers().containsKey(questionId))) {
            clearDailySession(questionBankId);
        }
        WrongSession wrongSession = getWrongSession(questionBankId);
        if (wrongSession != null && wrongSession.getQuestionIds().contains(questionId)) {
            clearWrongSession(questionBankId);
        }
    }

    public void clearQuestionBankData(String questionBankId) {
        clearTypeSessions(questionBankId);
        preferences.edit()
                .remove(namespacedKey(KEY_WRONG_BOOK, questionBankId))
                .remove(namespacedKey(KEY_WRONG_SESSION, questionBankId))
                .remove(namespacedKey(KEY_DAILY_SESSION, questionBankId))
                .remove(namespacedKey(KEY_DAILY_ORDER_REVERSED, questionBankId))
                .remove(namespacedKey(KEY_DAILY_HISTORY, questionBankId))
                .remove(namespacedKey(KEY_FAVORITES, questionBankId))
                .apply();
    }

    public WrongSession getWrongSession(String questionBankId) {
        try {
            String raw = preferences.getString(namespacedKey(KEY_WRONG_SESSION, questionBankId), null);
            if (raw == null || raw.isEmpty()) {
                return null;
            }

            JSONObject object = new JSONObject(raw);
            List<String> questionIds = readStringList(object.optJSONArray("questionIds"));
            if (questionIds.isEmpty()) {
                return null;
            }
            return new WrongSession(
                    questionIds,
                    object.optInt("currentIndex", 0),
                    object.optInt("answeredCount", 0),
                    object.optInt("correctCount", 0),
                    object.optBoolean("answerRevealed", false),
                    readAnswerStates(object.optJSONArray("answerStates")),
                    readDraftAnswers(object.optJSONArray("draftAnswers")),
                    readMatchingDrafts(object.optJSONArray("matchingDrafts"))
            );
        } catch (Exception e) {
            return null;
        }
    }

    public void saveWrongSession(String questionBankId, WrongSession session) {
        JSONObject object = new JSONObject();
        try {
            object.put("questionIds", toArray(session.getQuestionIds()));
            object.put("currentIndex", session.getCurrentIndex());
            object.put("answeredCount", session.getAnsweredCount());
            object.put("correctCount", session.getCorrectCount());
            object.put("answerRevealed", session.isAnswerRevealed());
            object.put("answerStates", writeAnswerStates(session.getAnswerStates()));
            object.put("draftAnswers", writeDraftAnswers(session.getDraftAnswers()));
            object.put("matchingDrafts", writeMatchingDrafts(session.getMatchingDrafts()));
        } catch (Exception e) {
            throw new RuntimeException("保存错题本进度失败", e);
        }
        preferences.edit()
                .putString(namespacedKey(KEY_WRONG_SESSION, questionBankId), object.toString())
                .apply();
    }

    public void clearWrongSession(String questionBankId) {
        preferences.edit().remove(namespacedKey(KEY_WRONG_SESSION, questionBankId)).apply();
    }

    public DailySession getDailySession(String questionBankId) {
        return getPracticeSession(namespacedKey(KEY_DAILY_SESSION, questionBankId));
    }

    public DailySession getTypeSession(String bankId, String type) {
        return getPracticeSession(TypePracticeRules.sessionKey(bankId, type));
    }

    private DailySession getPracticeSession(String key) {
        try {
            String raw = preferences.getString(key, null);
            if (raw == null || raw.isEmpty()) {
                return null;
            }

            JSONObject object = new JSONObject(raw);
            JSONArray answersArray = object.optJSONArray("selectedAnswers");
            List<String> selectedAnswers = readStringList(answersArray);
            Map<String, AnswerState> answerStates = readAnswerStates(object.optJSONArray("answerStates"));
            Map<String, List<String>> draftAnswers = readDraftAnswers(object.optJSONArray("draftAnswers"));
            Map<String, List<MatchingData.Pair>> matchingDrafts = readMatchingDrafts(
                    object.optJSONArray("matchingDrafts"));

            return new DailySession(
                    object.optString("questionId", ""),
                    object.optInt("currentIndex", 0),
                    object.optInt("answeredCount", 0),
                    object.optInt("correctCount", 0),
                    object.optBoolean("answerRevealed", false),
                    selectedAnswers,
                    answerStates,
                    draftAnswers,
                    matchingDrafts
            );
        } catch (Exception e) {
            return null;
        }
    }

    public void saveDailySession(String questionBankId, DailySession session) {
        savePracticeSession(namespacedKey(KEY_DAILY_SESSION, questionBankId), session);
    }

    public void saveTypeSession(String bankId, String type, DailySession session) {
        savePracticeSession(TypePracticeRules.sessionKey(bankId, type), session);
    }

    private void savePracticeSession(String key, DailySession session) {
        JSONObject object = new JSONObject();
        try {
            object.put("questionId", session.getQuestionId());
            object.put("currentIndex", session.getCurrentIndex());
            object.put("answeredCount", session.getAnsweredCount());
            object.put("correctCount", session.getCorrectCount());
            object.put("answerRevealed", session.isAnswerRevealed());
            object.put("selectedAnswers", toArray(session.getSelectedAnswers()));
            object.put("answerStates", writeAnswerStates(session.getAnswerStates()));
            object.put("draftAnswers", writeDraftAnswers(session.getDraftAnswers()));
            object.put("matchingDrafts", writeMatchingDrafts(session.getMatchingDrafts()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save daily session", e);
        }
        preferences.edit()
                .putString(key, object.toString())
                .apply();
    }

    public void clearTypeSession(String bankId, String type) {
        preferences.edit().remove(TypePracticeRules.sessionKey(bankId, type)).apply();
    }

    public void clearTypeSessions(String bankId) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String type : TypePracticeRules.TYPES) editor.remove(TypePracticeRules.sessionKey(bankId, type));
        editor.apply();
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
                        item.optInt("wrongCount", 0),
                        item.optString("detailId", "")
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
            for (int i = 0; i < records.size() - 50; i++) {
                dailyHistoryStore.delete(records.get(i).getDetailId());
            }
            records = new ArrayList<>(records.subList(records.size() - 50, records.size()));
        }

        JSONArray array = new JSONArray();
        try {
            for (DailyPracticeRecord item : records) {
                JSONObject object = new JSONObject();
                object.put("timestamp", item.getTimestamp());
                object.put("correctCount", item.getCorrectCount());
                object.put("wrongCount", item.getWrongCount());
                object.put("detailId", item.getDetailId());
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
        for (DailyPracticeRecord record : getDailyPracticeRecords(questionBankId)) {
            dailyHistoryStore.delete(record.getDetailId());
        }
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

    private void saveFavorites(String questionBankId, Set<String> favorites) {
        JSONArray array = new JSONArray();
        for (String questionId : favorites) {
            array.put(questionId);
        }
        preferences.edit()
                .putString(namespacedKey(KEY_FAVORITES, questionBankId), array.toString())
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

    private Map<String, AnswerState> readAnswerStates(JSONArray statesArray) {
        Map<String, AnswerState> answerStates = new HashMap<>();
        if (statesArray == null) {
            return answerStates;
        }
        for (int i = 0; i < statesArray.length(); i++) {
            JSONObject stateObject = statesArray.optJSONObject(i);
            if (stateObject == null) {
                continue;
            }
            String questionId = stateObject.optString("questionId", "");
            if (questionId.isEmpty()) {
                continue;
            }
            answerStates.put(questionId, new AnswerState(
                    questionId,
                    readStringList(stateObject.optJSONArray("selectedAnswers")),
                    stateObject.optBoolean("answered", false),
                    stateObject.optBoolean("correct", false),
                    stateObject.optBoolean("corrected", false),
                    readPairs(stateObject.optJSONArray("selectedPairs"))
            ));
        }
        return answerStates;
    }

    private Map<String, List<String>> readDraftAnswers(JSONArray draftsArray) {
        Map<String, List<String>> draftAnswers = new HashMap<>();
        if (draftsArray == null) {
            return draftAnswers;
        }
        for (int i = 0; i < draftsArray.length(); i++) {
            JSONObject draftObject = draftsArray.optJSONObject(i);
            if (draftObject == null) {
                continue;
            }
            String questionId = draftObject.optString("questionId", "");
            if (!questionId.isEmpty()) {
                draftAnswers.put(
                        questionId,
                        readStringList(draftObject.optJSONArray("selectedAnswers"))
                );
            }
        }
        return draftAnswers;
    }

    private JSONArray writeAnswerStates(Map<String, AnswerState> answerStates) {
        JSONArray statesArray = new JSONArray();
        for (AnswerState state : answerStates.values()) {
            JSONObject stateObject = new JSONObject();
            try {
                stateObject.put("questionId", state.getQuestionId());
                stateObject.put("selectedAnswers", toArray(state.getSelectedAnswers()));
                stateObject.put("selectedPairs", MatchingData.pairsToJson(state.getSelectedPairs()));
                stateObject.put("answered", state.isAnswered());
                stateObject.put("correct", state.isCorrect());
                stateObject.put("corrected", state.isCorrected());
            } catch (Exception e) {
                throw new RuntimeException("保存答题状态失败", e);
            }
            statesArray.put(stateObject);
        }
        return statesArray;
    }

    private JSONArray writeDraftAnswers(Map<String, List<String>> draftAnswers) {
        JSONArray draftsArray = new JSONArray();
        for (Map.Entry<String, List<String>> entry : draftAnswers.entrySet()) {
            JSONObject draftObject = new JSONObject();
            try {
                draftObject.put("questionId", entry.getKey());
                draftObject.put("selectedAnswers", toArray(entry.getValue()));
            } catch (Exception e) {
                throw new RuntimeException("保存草稿答案失败", e);
            }
            draftsArray.put(draftObject);
        }
        return draftsArray;
    }

    private Map<String, List<MatchingData.Pair>> readMatchingDrafts(JSONArray draftsArray) {
        Map<String, List<MatchingData.Pair>> drafts = new HashMap<>();
        if (draftsArray == null) return drafts;
        for (int i = 0; i < draftsArray.length(); i++) {
            JSONObject row = draftsArray.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("questionId", "");
            if (!id.isEmpty()) drafts.put(id, readPairs(row.optJSONArray("selectedPairs")));
        }
        return drafts;
    }

    private JSONArray writeMatchingDrafts(Map<String, List<MatchingData.Pair>> drafts) throws Exception {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, List<MatchingData.Pair>> entry : drafts.entrySet()) {
            result.put(new JSONObject().put("questionId", entry.getKey())
                    .put("selectedPairs", MatchingData.pairsToJson(entry.getValue())));
        }
        return result;
    }

    private static List<MatchingData.Pair> readPairs(JSONArray array) {
        try { return MatchingData.pairsFromJson(array); }
        catch (Exception ignored) { return Collections.emptyList(); }
    }

    private static Map<String, List<MatchingData.Pair>> copyMatchingDrafts(
            Map<String, List<MatchingData.Pair>> source) {
        Map<String, List<MatchingData.Pair>> result = new HashMap<>();
        if (source != null) for (Map.Entry<String, List<MatchingData.Pair>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
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
        private final Map<String, List<String>> draftAnswers;
        private final Map<String, List<MatchingData.Pair>> matchingDrafts;

        public DailySession(
                String questionId,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                List<String> selectedAnswers
        ) {
            this(
                    questionId,
                    currentIndex,
                    answeredCount,
                    correctCount,
                    answerRevealed,
                    selectedAnswers,
                    Collections.emptyMap(),
                    Collections.emptyMap()
            );
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
            this(
                    questionId,
                    currentIndex,
                    answeredCount,
                    correctCount,
                    answerRevealed,
                    selectedAnswers,
                    answerStates,
                    Collections.emptyMap()
            );
        }

        public DailySession(
                String questionId,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                List<String> selectedAnswers,
                Map<String, AnswerState> answerStates,
                Map<String, List<String>> draftAnswers
        ) {
            this(questionId, currentIndex, answeredCount, correctCount, answerRevealed,
                    selectedAnswers, answerStates, draftAnswers, Collections.emptyMap());
        }

        public DailySession(
                String questionId,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                List<String> selectedAnswers,
                Map<String, AnswerState> answerStates,
                Map<String, List<String>> draftAnswers,
                Map<String, List<MatchingData.Pair>> matchingDrafts
        ) {
            this.questionId = questionId;
            this.currentIndex = currentIndex;
            this.answeredCount = answeredCount;
            this.correctCount = correctCount;
            this.answerRevealed = answerRevealed;
            this.selectedAnswers = Collections.unmodifiableList(new ArrayList<>(selectedAnswers));
            this.answerStates = Collections.unmodifiableMap(new HashMap<>(answerStates));
            Map<String, List<String>> immutableDraftAnswers = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : draftAnswers.entrySet()) {
                immutableDraftAnswers.put(
                        entry.getKey(),
                        Collections.unmodifiableList(new ArrayList<>(entry.getValue()))
                );
            }
            this.draftAnswers = Collections.unmodifiableMap(immutableDraftAnswers);
            this.matchingDrafts = copyMatchingDrafts(matchingDrafts);
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

        public Map<String, List<String>> getDraftAnswers() {
            return draftAnswers;
        }

        public Map<String, List<MatchingData.Pair>> getMatchingDrafts() { return matchingDrafts; }
    }

    public static class WrongSession {
        private final List<String> questionIds;
        private final int currentIndex;
        private final int answeredCount;
        private final int correctCount;
        private final boolean answerRevealed;
        private final Map<String, AnswerState> answerStates;
        private final Map<String, List<String>> draftAnswers;
        private final Map<String, List<MatchingData.Pair>> matchingDrafts;

        public WrongSession(
                List<String> questionIds,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                Map<String, AnswerState> answerStates,
                Map<String, List<String>> draftAnswers
        ) {
            this(questionIds, currentIndex, answeredCount, correctCount, answerRevealed,
                    answerStates, draftAnswers, Collections.emptyMap());
        }

        public WrongSession(
                List<String> questionIds,
                int currentIndex,
                int answeredCount,
                int correctCount,
                boolean answerRevealed,
                Map<String, AnswerState> answerStates,
                Map<String, List<String>> draftAnswers,
                Map<String, List<MatchingData.Pair>> matchingDrafts
        ) {
            this.questionIds = Collections.unmodifiableList(new ArrayList<>(questionIds));
            this.currentIndex = currentIndex;
            this.answeredCount = answeredCount;
            this.correctCount = correctCount;
            this.answerRevealed = answerRevealed;
            this.answerStates = Collections.unmodifiableMap(new HashMap<>(answerStates));
            Map<String, List<String>> immutableDraftAnswers = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : draftAnswers.entrySet()) {
                immutableDraftAnswers.put(
                        entry.getKey(),
                        Collections.unmodifiableList(new ArrayList<>(entry.getValue()))
                );
            }
            this.draftAnswers = Collections.unmodifiableMap(immutableDraftAnswers);
            this.matchingDrafts = copyMatchingDrafts(matchingDrafts);
        }

        public List<String> getQuestionIds() {
            return questionIds;
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

        public Map<String, AnswerState> getAnswerStates() {
            return answerStates;
        }

        public Map<String, List<String>> getDraftAnswers() {
            return draftAnswers;
        }

        public Map<String, List<MatchingData.Pair>> getMatchingDrafts() { return matchingDrafts; }
    }

    public static class AnswerState {
        private final String questionId;
        private final List<String> selectedAnswers;
        private final List<MatchingData.Pair> selectedPairs;
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
            this(questionId, selectedAnswers, answered, correct, corrected,
                    Collections.emptyList());
        }

        public AnswerState(
                String questionId,
                List<String> selectedAnswers,
                boolean answered,
                boolean correct,
                boolean corrected,
                List<MatchingData.Pair> selectedPairs
        ) {
            this.questionId = questionId;
            this.selectedAnswers = Collections.unmodifiableList(new ArrayList<>(selectedAnswers));
            this.selectedPairs = Collections.unmodifiableList(new ArrayList<>(selectedPairs));
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

        public List<MatchingData.Pair> getSelectedPairs() { return selectedPairs; }

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
        private final String detailId;

        public DailyPracticeRecord(long timestamp, int correctCount, int wrongCount) {
            this(timestamp, correctCount, wrongCount, "");
        }

        public DailyPracticeRecord(long timestamp, int correctCount, int wrongCount, String detailId) {
            this.timestamp = timestamp;
            this.correctCount = correctCount;
            this.wrongCount = wrongCount;
            this.detailId = detailId == null ? "" : detailId;
        }

        public String getDetailId() { return detailId; }

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
