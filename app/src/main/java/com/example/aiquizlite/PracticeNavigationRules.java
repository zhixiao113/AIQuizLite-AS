package com.example.aiquizlite;

import java.util.function.IntPredicate;

/** Navigation after submitting an answer, including questions skipped via the answer sheet. */
final class PracticeNavigationRules {
    private PracticeNavigationRules() { }

    static int nextIndex(int currentIndex, int total, IntPredicate isAnswered) {
        if (total <= 0) {
            return -1;
        }
        if (currentIndex < total - 1) {
            return currentIndex + 1;
        }
        // Reaching the last question no longer means every question was answered.
        for (int index = 0; index < total; index++) {
            if (!isAnswered.test(index)) {
                return index;
            }
        }
        return -1;
    }
}
