package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MaterialTextRules {
    public static final int MAX_SELECTED_CHARACTERS = 100_000;
    public static final int DEFAULT_CHUNK_CHARACTERS = 6_000;
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^(?:第[0-9一二三四五六七八九十百]+[章节篇部]|[0-9]{1,3}[.、．]\s*|[一二三四五六七八九十]+[、.．]\s*).{0,56}$"
    );

    private MaterialTextRules() {
    }

    public static List<Chapter> detectChapters(String rawText) {
        String text = normalizeText(rawText);
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = text.split("\n");
        List<Chapter> chapters = new ArrayList<>();
        String currentTitle = null;
        StringBuilder currentContent = new StringBuilder();
        StringBuilder preamble = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                appendLine(currentTitle == null ? preamble : currentContent, "");
                continue;
            }
            if (isChapterHeading(line)) {
                if (currentTitle != null) {
                    chapters.add(new Chapter(currentTitle, currentContent.toString().trim()));
                }
                currentTitle = line;
                currentContent.setLength(0);
                if (chapters.isEmpty() && preamble.length() > 0) {
                    appendLine(currentContent, preamble.toString().trim());
                }
            } else {
                appendLine(currentTitle == null ? preamble : currentContent, line);
            }
        }
        if (currentTitle != null) {
            chapters.add(new Chapter(currentTitle, currentContent.toString().trim()));
        }
        if (chapters.isEmpty()) {
            chapters.add(new Chapter("全文", text));
        }
        return Collections.unmodifiableList(withUniqueTitles(chapters));
    }

    public static List<TextChunk> chunk(List<Chapter> chapters, int maximumCharacters) {
        int max = Math.max(1, maximumCharacters);
        List<TextChunk> result = new ArrayList<>();
        if (chapters == null) {
            return result;
        }
        for (Chapter chapter : chapters) {
            if (chapter == null || chapter.getContent().isEmpty()) {
                continue;
            }
            String remaining = chapter.getContent();
            while (!remaining.isEmpty()) {
                int end = Math.min(max, remaining.length());
                if (end < remaining.length()) {
                    int naturalBreak = bestBreak(remaining, end);
                    if (naturalBreak > Math.max(1, max / 2)) {
                        end = naturalBreak;
                    }
                }
                String value = remaining.substring(0, end).trim();
                if (!value.isEmpty()) {
                    result.add(new TextChunk(chapter.getTitle(), value));
                }
                remaining = remaining.substring(end).trim();
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static List<TextChunk> chunk(List<Chapter> chapters) {
        return chunk(chapters, DEFAULT_CHUNK_CHARACTERS);
    }

    public static int recommendQuestionCount(int selectedCharacterCount) {
        if (selectedCharacterCount <= 0) {
            return 0;
        }
        int raw = Math.max(10, (int) Math.ceil(selectedCharacterCount / 800.0));
        int rounded = ((raw + 4) / 5) * 5;
        return Math.min(100, rounded);
    }

    public static int countCharacters(List<Chapter> chapters) {
        int total = 0;
        if (chapters != null) {
            for (Chapter chapter : chapters) {
                if (chapter != null) {
                    total += chapter.getContent().length();
                }
            }
        }
        return total;
    }

    public static String normalizeText(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static boolean isChapterHeading(String line) {
        if (line.length() > 60 || line.endsWith("。") || line.endsWith("？") || line.endsWith("！")) {
            return false;
        }
        return CHAPTER_HEADING.matcher(line).matches();
    }

    private static int bestBreak(String text, int preferredEnd) {
        int newline = text.lastIndexOf('\n', preferredEnd);
        int sentence = Math.max(text.lastIndexOf('。', preferredEnd), text.lastIndexOf('；', preferredEnd));
        return Math.max(newline, sentence < 0 ? -1 : sentence + 1);
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private static List<Chapter> withUniqueTitles(List<Chapter> chapters) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<Chapter> result = new ArrayList<>();
        for (Chapter chapter : chapters) {
            int occurrence = occurrences.getOrDefault(chapter.getTitle(), 0) + 1;
            occurrences.put(chapter.getTitle(), occurrence);
            String title = occurrence == 1
                    ? chapter.getTitle()
                    : chapter.getTitle() + "（" + occurrence + "）";
            result.add(new Chapter(title, chapter.getContent()));
        }
        return result;
    }

    public static final class Chapter {
        private final String title;
        private final String content;

        public Chapter(String title, String content) {
            this.title = title == null || title.trim().isEmpty() ? "全文" : title.trim();
            this.content = normalizeText(content);
        }

        public String getTitle() {
            return title;
        }

        public String getContent() {
            return content;
        }

        public int getCharacterCount() {
            return content.length();
        }
    }

    public static final class TextChunk {
        private final String chapterTitle;
        private final String text;

        TextChunk(String chapterTitle, String text) {
            this.chapterTitle = chapterTitle;
            this.text = text;
        }

        public String getChapterTitle() {
            return chapterTitle;
        }

        public String getText() {
            return text;
        }
    }
}
