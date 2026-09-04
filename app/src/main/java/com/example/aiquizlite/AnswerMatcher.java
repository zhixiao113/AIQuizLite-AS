package com.example.aiquizlite;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AnswerMatcher {
    private static final Pattern LEADING_ANSWER_NUMBER = Pattern.compile(
            "^\\s*[（(]\\s*\\d+\\s*[)）]\\s*"
    );
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEADING_QUOTES = Pattern.compile("^[\\\"'“”‘’]+\\s*");
    private static final Pattern TRAILING_PRESENTATION_PUNCTUATION = Pattern.compile(
            "\\s*[。．.!！?？,，;；:：\\\"'“”‘’]+\\s*$"
    );

    private AnswerMatcher() {
    }

    public static boolean matchesFillBlank(String userAnswer, List<String> acceptedAnswers) {
        String normalizedUserAnswer = normalizeFillBlank(userAnswer);
        if (normalizedUserAnswer.isEmpty() || acceptedAnswers == null) {
            return false;
        }
        for (String acceptedAnswer : acceptedAnswers) {
            if (normalizedUserAnswer.equals(normalizeFillBlank(acceptedAnswer))) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsEquivalentAnswer(List<String> answers, String candidate) {
        String normalizedCandidate = normalizeFillBlank(candidate);
        if (normalizedCandidate.isEmpty() || answers == null) {
            return false;
        }
        for (String answer : answers) {
            if (normalizedCandidate.equals(normalizeFillBlank(answer))) {
                return true;
            }
        }
        return false;
    }

    public static String cleanFillBlankForStorage(String answer) {
        if (answer == null) {
            return "";
        }
        String cleaned = Normalizer.normalize(answer, Normalizer.Form.NFKC);
        cleaned = LEADING_ANSWER_NUMBER.matcher(cleaned).replaceFirst("");
        cleaned = LEADING_QUOTES.matcher(cleaned).replaceFirst("");
        cleaned = TRAILING_PRESENTATION_PUNCTUATION.matcher(cleaned).replaceFirst("");
        return REPEATED_WHITESPACE.matcher(cleaned.trim()).replaceAll(" ");
    }

    static String normalizeFillBlank(String answer) {
        return cleanFillBlankForStorage(answer).toLowerCase(Locale.ROOT);
    }
}
