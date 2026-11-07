package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only model-marked terms become interactive; unmarked legacy explanations remain readable. */
final class AiTermMarkup {
    private static final Pattern TERM = Pattern.compile("\\[\\[([^\\[\\]\\r\\n]{1,80})\\]\\]");

    static final class Range {
        final int start;
        final int end;
        final String term;

        Range(int start, int end, String term) {
            this.start = start;
            this.end = end;
            this.term = term;
        }
    }

    static final class Parsed {
        final String text;
        final List<Range> ranges;

        Parsed(String text, List<Range> ranges) {
            this.text = text;
            this.ranges = Collections.unmodifiableList(ranges);
        }
    }

    private AiTermMarkup() {}

    static Parsed parse(String marked) {
        String original = marked == null ? "" : marked;
        Matcher matcher = TERM.matcher(original);
        StringBuilder clean = new StringBuilder();
        List<Range> ranges = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            clean.append(original, cursor, matcher.start());
            String term = matcher.group(1).trim();
            int start = clean.length();
            clean.append(term);
            if (!term.isEmpty()) ranges.add(new Range(start, clean.length(), term));
            cursor = matcher.end();
        }
        clean.append(original, cursor, original.length());
        return new Parsed(clean.toString(), ranges);
    }
}
