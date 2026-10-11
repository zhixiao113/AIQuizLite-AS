package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative original-number audit for existing exam papers with explicit type labels. */
final class SourceQuestionAudit {
    private static final Pattern NUMBERED_QUESTION = Pattern.compile(
            "(?m)^\\s*(\\d{1,4})[.．、]\\s*[（(]\\s*(?:单选题|多选题|判断题|填空题|连线题)");

    private SourceQuestionAudit() { }

    static List<Integer> sourceNumbers(String source) {
        Set<Integer> numbers = new LinkedHashSet<>();
        Matcher matcher = NUMBERED_QUESTION.matcher(source == null ? "" : source);
        while (matcher.find()) numbers.add(Integer.parseInt(matcher.group(1)));
        return new ArrayList<>(numbers);
    }

    static List<Integer> missingNumbers(String source, JSONArray accepted) {
        Set<Integer> missing = new LinkedHashSet<>(sourceNumbers(source));
        if (accepted != null) for (int i = 0; i < accepted.length(); i++) {
            JSONObject item = accepted.optJSONObject(i);
            if (item != null && item.optInt("numberInPaper", 0) > 0) {
                missing.remove(item.optInt("numberInPaper"));
            }
        }
        return new ArrayList<>(missing);
    }

    static List<Integer> duplicateNumbers(JSONArray accepted) {
        Set<Integer> seen = new LinkedHashSet<>();
        Set<Integer> duplicate = new LinkedHashSet<>();
        if (accepted != null) for (int i = 0; i < accepted.length(); i++) {
            JSONObject item = accepted.optJSONObject(i);
            int number = item == null ? 0 : item.optInt("numberInPaper", 0);
            if (number > 0 && !seen.add(number)) duplicate.add(number);
        }
        return new ArrayList<>(duplicate);
    }

    static List<Integer> unexpectedNumbers(String source, JSONArray accepted) {
        Set<Integer> expected = new LinkedHashSet<>(sourceNumbers(source));
        Set<Integer> unexpected = new LinkedHashSet<>();
        if (accepted != null && !expected.isEmpty()) for (int i = 0; i < accepted.length(); i++) {
            JSONObject item = accepted.optJSONObject(i);
            int number = item == null ? 0 : item.optInt("numberInPaper", 0);
            if (number <= 0 || !expected.contains(number)) unexpected.add(number);
        }
        return new ArrayList<>(unexpected);
    }
}
