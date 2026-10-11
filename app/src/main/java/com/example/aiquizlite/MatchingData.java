package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable-ID matching question content and answer rules; screen coordinates are never stored. */
public final class MatchingData {
    public static final String ONE_TO_ONE = "one_to_one";
    public static final String LEFT_REUSABLE = "left_reusable";

    private final String mode;
    private final List<Item> leftItems;
    private final List<Item> rightItems;
    private final List<Pair> correctPairs;

    public MatchingData(String mode, List<Item> leftItems, List<Item> rightItems,
                        List<Pair> correctPairs) {
        if (!ONE_TO_ONE.equals(mode) && !LEFT_REUSABLE.equals(mode)) {
            throw new IllegalArgumentException("不支持的连线规则");
        }
        this.mode = mode;
        this.leftItems = immutableItems(leftItems, "左侧");
        this.rightItems = immutableItems(rightItems, "右侧");
        if (this.leftItems.isEmpty() || this.rightItems.isEmpty()) {
            throw new IllegalArgumentException("连线题左右项目不能为空");
        }
        this.correctPairs = immutablePairs(correctPairs);
        if (!isValidSelection(this.correctPairs) || !isComplete(this.correctPairs)) {
            throw new IllegalArgumentException("连线题标准关系不完整或不符合规则");
        }
    }

    private static List<Item> immutableItems(List<Item> source, String side) {
        if (source == null) throw new IllegalArgumentException(side + "项目不能为空");
        List<Item> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Item item : source) {
            if (item == null || item.id.isEmpty() || item.text.isEmpty() || !ids.add(item.id)) {
                throw new IllegalArgumentException(side + "项目 ID 重复或内容为空");
            }
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Pair> immutablePairs(List<Pair> source) {
        if (source == null) throw new IllegalArgumentException("连线关系不能为空");
        List<Pair> result = new ArrayList<>();
        for (Pair pair : source) {
            if (pair == null || pair.leftId.isEmpty() || pair.rightId.isEmpty()) {
                throw new IllegalArgumentException("连线关系包含空项目");
            }
            result.add(pair);
        }
        return Collections.unmodifiableList(result);
    }

    public String getMode() { return mode; }
    public List<Item> getLeftItems() { return leftItems; }
    public List<Item> getRightItems() { return rightItems; }
    public List<Pair> getCorrectPairs() { return correctPairs; }

    public boolean isValidSelection(List<Pair> pairs) {
        if (pairs == null) return false;
        Set<String> leftIds = ids(leftItems);
        Set<String> rightIds = ids(rightItems);
        Set<String> usedLeft = new HashSet<>();
        Set<String> usedRight = new HashSet<>();
        for (Pair pair : pairs) {
            if (pair == null || !leftIds.contains(pair.leftId) || !rightIds.contains(pair.rightId)
                    || !usedRight.add(pair.rightId)
                    || (ONE_TO_ONE.equals(mode) && !usedLeft.add(pair.leftId))) {
                return false;
            }
        }
        return true;
    }

    public boolean isComplete(List<Pair> pairs) {
        return isValidSelection(pairs) && pairs.size() == rightItems.size();
    }

    public boolean isCorrect(List<Pair> pairs) {
        return isComplete(pairs) && byRight(pairs).equals(byRight(correctPairs));
    }

    public int countCorrect(List<Pair> pairs) {
        if (!isValidSelection(pairs)) return 0;
        Map<String, String> expected = byRight(correctPairs);
        int count = 0;
        for (Pair pair : pairs) {
            if (pair.leftId.equals(expected.get(pair.rightId))) count++;
        }
        return count;
    }

    public String leftText(String id) {
        for (Item item : leftItems) if (item.id.equals(id)) return item.text;
        return id;
    }

    public String rightText(String id) {
        for (Item item : rightItems) if (item.id.equals(id)) return item.text;
        return id;
    }

    public JSONObject toJson() throws Exception {
        JSONObject result = new JSONObject().put("mode", mode);
        JSONArray left = new JSONArray();
        JSONArray right = new JSONArray();
        for (Item item : leftItems) left.put(item.toJson());
        for (Item item : rightItems) right.put(item.toJson());
        return result.put("leftItems", left).put("rightItems", right)
                .put("correctPairs", pairsToJson(correctPairs));
    }

    public static MatchingData fromJson(JSONObject source) throws Exception {
        if (source == null) throw new IllegalArgumentException("缺少连线题结构");
        return new MatchingData(source.getString("mode"),
                readItems(source.getJSONArray("leftItems")),
                readItems(source.getJSONArray("rightItems")),
                pairsFromJson(source.getJSONArray("correctPairs")));
    }

    public static JSONArray pairsToJson(List<Pair> pairs) throws Exception {
        JSONArray result = new JSONArray();
        if (pairs != null) for (Pair pair : pairs) result.put(pair.toJson());
        return result;
    }

    public static List<Pair> pairsFromJson(JSONArray source) throws Exception {
        List<Pair> result = new ArrayList<>();
        if (source == null) return result;
        for (int i = 0; i < source.length(); i++) {
            JSONObject pair = source.getJSONObject(i);
            result.add(new Pair(pair.getString("leftId"), pair.getString("rightId")));
        }
        return result;
    }

    private static List<Item> readItems(JSONArray source) throws Exception {
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            result.add(new Item(item.getString("id"), item.getString("text")));
        }
        return result;
    }

    private static Set<String> ids(List<Item> items) {
        Set<String> result = new HashSet<>();
        for (Item item : items) result.add(item.id);
        return result;
    }

    private static Map<String, String> byRight(List<Pair> pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Pair pair : pairs) result.put(pair.rightId, pair.leftId);
        return result;
    }

    public static final class Item {
        private final String id;
        private final String text;

        public Item(String id, String text) {
            this.id = id == null ? "" : id.trim();
            this.text = text == null ? "" : text.trim();
        }

        public String getId() { return id; }
        public String getText() { return text; }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("text", text);
        }
    }

    public static final class Pair {
        private final String leftId;
        private final String rightId;

        public Pair(String leftId, String rightId) {
            this.leftId = leftId == null ? "" : leftId.trim();
            this.rightId = rightId == null ? "" : rightId.trim();
        }

        public String getLeftId() { return leftId; }
        public String getRightId() { return rightId; }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("leftId", leftId).put("rightId", rightId);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Pair)) return false;
            Pair pair = (Pair) other;
            return leftId.equals(pair.leftId) && rightId.equals(pair.rightId);
        }

        @Override public int hashCode() { return Objects.hash(leftId, rightId); }

        @Override public String toString() { return leftId + "->" + rightId; }
    }
}
