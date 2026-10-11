package com.example.aiquizlite;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

/** One answer and undo stack shared by the compact and expanded editors. */
final class MatchingSelectionState {
    private final MatchingData data;
    private final LinkedHashMap<String, String> selected = new LinkedHashMap<>();
    private final Deque<LinkedHashMap<String, String>> history = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();

    MatchingSelectionState(MatchingData data) { this.data = data; }
    void addListener(Runnable listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }
    String sourceFor(String rightId) { return selected.get(rightId); }
    int size() { return selected.size(); }
    boolean canUndo() { return !history.isEmpty(); }

    List<MatchingData.Pair> pairs() {
        List<MatchingData.Pair> result = new ArrayList<>();
        for (MatchingData.Item right : data.getRightItems()) {
            String left = selected.get(right.getId());
            if (left != null) result.add(new MatchingData.Pair(left, right.getId()));
        }
        return result;
    }

    void restore(List<MatchingData.Pair> pairs) {
        if (!data.isValidSelection(pairs)) throw new IllegalArgumentException("作答连线不符合题目规则");
        selected.clear();
        for (MatchingData.Pair pair : pairs) selected.put(pair.getRightId(), pair.getLeftId());
        history.clear();
        changed();
    }

    boolean connect(String leftId, String rightId) {
        if (leftId.equals(selected.get(rightId))) return true;
        List<MatchingData.Pair> candidate = pairs();
        candidate.removeIf(pair -> rightId.equals(pair.getRightId()));
        candidate.add(new MatchingData.Pair(leftId, rightId));
        if (!data.isValidSelection(candidate)) return false;
        remember();
        selected.put(rightId, leftId);
        changed();
        return true;
    }

    void remove(String rightId) {
        if (!selected.containsKey(rightId)) return;
        remember(); selected.remove(rightId); changed();
    }
    void clear() {
        if (selected.isEmpty()) return;
        remember(); selected.clear(); changed();
    }
    void undo() {
        if (history.isEmpty()) return;
        selected.clear(); selected.putAll(history.pop()); changed();
    }
    private void remember() { history.push(new LinkedHashMap<>(selected)); }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
}
