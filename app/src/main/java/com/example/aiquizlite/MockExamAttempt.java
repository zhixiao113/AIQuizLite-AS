package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class MockExamAttempt {
    private final String id;
    private final String questionBankId;
    private final long createdAt;
    private long completedAt;
    private boolean completed;
    private int currentIndex;
    private final List<Item> items;

    private MockExamAttempt(
            String id,
            String questionBankId,
            long createdAt,
            long completedAt,
            boolean completed,
            int currentIndex,
            List<Item> items
    ) {
        this.id = id;
        this.questionBankId = questionBankId;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.completed = completed;
        this.currentIndex = currentIndex;
        this.items = new ArrayList<>(items);
    }

    public static MockExamAttempt create(
            String questionBankId,
            List<Question> sourceQuestions,
            Random random,
            long now
    ) {
        return create(questionBankId, sourceQuestions, random, now, 0, true, true);
    }

    public static MockExamAttempt create(
            String questionBankId,
            List<Question> sourceQuestions,
            Random random,
            long now,
            int questionLimit,
            boolean shuffleQuestions,
            boolean shuffleOptions
    ) {
        List<Question> objectiveQuestions = new ArrayList<>();
        for (Question question : sourceQuestions) {
            if (!question.isFillBlank()) {
                objectiveQuestions.add(question);
            }
        }
        if (shuffleQuestions) {
            Collections.shuffle(objectiveQuestions, random);
        }
        int normalizedLimit = GeneralSettingsRules.normalizeMockQuestionLimit(questionLimit);
        if (normalizedLimit > 0 && objectiveQuestions.size() > normalizedLimit) {
            objectiveQuestions = new ArrayList<>(objectiveQuestions.subList(0, normalizedLimit));
        }
        List<Item> items = new ArrayList<>();
        for (Question question : objectiveQuestions) {
            items.add(new Item(
                    question.getId(),
                    shuffleOptions
                            ? MockOptionOrderRules.createOptionOrder(question, random)
                            : sourceOptionOrder(question),
                    question.getAnswers(),
                    Collections.emptyList(),
                    false,
                    question.isMatching() ? question.getMatching().getCorrectPairs() : Collections.emptyList(),
                    Collections.emptyList()
            ));
        }
        return new MockExamAttempt(
                UUID.randomUUID().toString(),
                questionBankId,
                now,
                0L,
                false,
                0,
                items
        );
    }

    private static List<String> sourceOptionOrder(Question question) {
        List<String> keys = new ArrayList<>();
        for (Question.Option option : question.getOptions()) {
            keys.add(option.getKey());
        }
        return keys;
    }

    public String getId() {
        return id;
    }

    public String getQuestionBankId() {
        return questionBankId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public boolean isCompleted() {
        return completed;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = Math.max(0, Math.min(currentIndex, Math.max(0, items.size() - 1)));
    }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Item findItem(String questionId) {
        for (Item item : items) {
            if (item.questionId.equals(questionId)) {
                return item;
            }
        }
        return null;
    }

    public void setSelection(String questionId, List<String> answers) {
        Item item = findItem(questionId);
        if (item != null && !completed) {
            item.selectedAnswers = immutableCopy(answers);
        }
    }

    public void setMatchingSelection(String questionId, List<MatchingData.Pair> pairs) {
        Item item = findItem(questionId);
        if (item != null && !completed && item.isMatching()) {
            item.selectedPairs = immutablePairs(pairs);
        }
    }

    public void markCompleted(long now) {
        completed = true;
        completedAt = now;
    }

    public void markCorrected(String questionId) {
        Item item = findItem(questionId);
        if (item != null) {
            item.corrected = true;
        }
    }

    public int getCorrectCount() {
        int count = 0;
        for (Item item : items) {
            if (item.isCorrect()) {
                count++;
            }
        }
        return count;
    }

    public int getWrongCount() {
        int count = 0;
        for (Item item : items) {
            if (item.isWrong()) {
                count++;
            }
        }
        return count;
    }

    public int getUnansweredCount() {
        int count = 0;
        for (Item item : items) {
            if (!item.isAnswered()) {
                count++;
            }
        }
        return count;
    }

    public int getScore() {
        return items.isEmpty() ? 0 : Math.round(getCorrectCount() * 100f / items.size());
    }

    public List<String> getWrongQuestionIds() {
        List<String> ids = new ArrayList<>();
        for (Item item : items) {
            if (item.isWrong()) {
                ids.add(item.questionId);
            }
        }
        return ids;
    }

    public String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("questionBankId", questionBankId);
            object.put("createdAt", createdAt);
            object.put("completedAt", completedAt);
            object.put("completed", completed);
            object.put("currentIndex", currentIndex);
            JSONArray itemArray = new JSONArray();
            for (Item item : items) {
                itemArray.put(item.toJson());
            }
            object.put("items", itemArray);
            return object.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法保存模拟考试记录", e);
        }
    }

    public static MockExamAttempt fromJson(String raw) {
        try {
            JSONObject object = new JSONObject(raw);
            JSONArray itemArray = object.getJSONArray("items");
            List<Item> items = new ArrayList<>();
            for (int index = 0; index < itemArray.length(); index++) {
                items.add(Item.fromJson(itemArray.getJSONObject(index)));
            }
            return new MockExamAttempt(
                    object.getString("id"),
                    object.getString("questionBankId"),
                    object.optLong("createdAt", 0L),
                    object.optLong("completedAt", 0L),
                    object.optBoolean("completed", false),
                    object.optInt("currentIndex", 0),
                    items
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> immutableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(
                source == null ? Collections.emptyList() : source
        ));
    }

    private static List<MatchingData.Pair> immutablePairs(List<MatchingData.Pair> source) {
        return Collections.unmodifiableList(new ArrayList<>(
                source == null ? Collections.emptyList() : source));
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static List<String> readArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                values.add(array.optString(index));
            }
        }
        return values;
    }

    public static final class Item {
        private final String questionId;
        private final List<String> optionKeyOrder;
        private final List<String> correctAnswers;
        private List<String> selectedAnswers;
        private final List<MatchingData.Pair> correctPairs;
        private List<MatchingData.Pair> selectedPairs;
        private boolean corrected;

        private Item(
                String questionId,
                List<String> optionKeyOrder,
                List<String> correctAnswers,
                List<String> selectedAnswers,
                boolean corrected,
                List<MatchingData.Pair> correctPairs,
                List<MatchingData.Pair> selectedPairs
        ) {
            this.questionId = questionId;
            this.optionKeyOrder = immutableCopy(optionKeyOrder);
            this.correctAnswers = immutableCopy(correctAnswers);
            this.selectedAnswers = immutableCopy(selectedAnswers);
            this.correctPairs = immutablePairs(correctPairs);
            this.selectedPairs = immutablePairs(selectedPairs);
            this.corrected = corrected;
        }

        public String getQuestionId() {
            return questionId;
        }

        public List<String> getOptionKeyOrder() {
            return optionKeyOrder;
        }

        public List<String> getCorrectAnswers() {
            return correctAnswers;
        }

        public List<String> getSelectedAnswers() {
            return selectedAnswers;
        }

        public List<MatchingData.Pair> getCorrectPairs() { return correctPairs; }
        public List<MatchingData.Pair> getSelectedPairs() { return selectedPairs; }
        public boolean isMatching() { return !correctPairs.isEmpty(); }
        public boolean isAnswered() {
            return isMatching() ? selectedPairs.size() == correctPairs.size()
                    : !selectedAnswers.isEmpty();
        }

        public boolean isCorrected() {
            return corrected;
        }

        public boolean isCorrect() {
            if (isMatching()) {
                return isAnswered() && new LinkedHashSet<>(selectedPairs)
                        .equals(new LinkedHashSet<>(correctPairs));
            }
            return sameAnswers(selectedAnswers, correctAnswers);
        }

        public boolean isWrong() {
            return isAnswered() && !isCorrect();
        }

        private JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("questionId", questionId);
            object.put("optionKeyOrder", toArray(optionKeyOrder));
            object.put("correctAnswers", toArray(correctAnswers));
            object.put("selectedAnswers", toArray(selectedAnswers));
            object.put("correctPairs", MatchingData.pairsToJson(correctPairs));
            object.put("selectedPairs", MatchingData.pairsToJson(selectedPairs));
            object.put("corrected", corrected);
            return object;
        }

        private static Item fromJson(JSONObject object) throws Exception {
            return new Item(
                    object.optString("questionId", ""),
                    readArray(object.optJSONArray("optionKeyOrder")),
                    readArray(object.optJSONArray("correctAnswers")),
                    readArray(object.optJSONArray("selectedAnswers")),
                    object.optBoolean("corrected", false),
                    MatchingData.pairsFromJson(object.optJSONArray("correctPairs")),
                    MatchingData.pairsFromJson(object.optJSONArray("selectedPairs"))
            );
        }

        private static boolean sameAnswers(List<String> selected, List<String> expected) {
            Set<String> selectedSet = new LinkedHashSet<>(selected);
            Set<String> expectedSet = new LinkedHashSet<>(expected);
            return !selectedSet.isEmpty() && selectedSet.equals(expectedSet);
        }
    }
}
