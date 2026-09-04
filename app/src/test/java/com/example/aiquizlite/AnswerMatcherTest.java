package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnswerMatcherTest {
    private static final String COMMAND = "display firewall session table verbose";

    @Test
    public void matchesIgnoringCaseWhitespaceAndLeadingAnswerNumber() {
        assertEquals(
                COMMAND,
                AnswerMatcher.normalizeFillBlank("  （1） DISPLAY  firewall\nsession table verbose。 ")
        );
        assertTrue(AnswerMatcher.matchesFillBlank(
                "  （1） DISPLAY  firewall\nsession table verbose。 ",
                Collections.singletonList(COMMAND)
        ));
    }

    @Test
    public void rejectsAbbreviatedMissingReorderedOrExplainedCommands() {
        assertFalse(AnswerMatcher.matchesFillBlank("dis firewall session table verbose", Collections.singletonList(COMMAND)));
        assertFalse(AnswerMatcher.matchesFillBlank("display firewall session table", Collections.singletonList(COMMAND)));
        assertFalse(AnswerMatcher.matchesFillBlank("display firewall session verbose table", Collections.singletonList(COMMAND)));
        assertFalse(AnswerMatcher.matchesFillBlank("答案是 " + COMMAND, Collections.singletonList(COMMAND)));
    }

    @Test
    public void acceptsAnyExplicitAnswerButDoesNotInferAliases() {
        assertTrue(AnswerMatcher.matchesFillBlank("DMZ", Arrays.asList("安全区域", "DMZ")));
        assertTrue(AnswerMatcher.matchesFillBlank("local", Collections.singletonList("Local")));
        assertFalse(AnswerMatcher.matchesFillBlank("Local区域", Collections.singletonList("Local")));
    }

    @Test
    public void keepsMeaningfulCommandSymbols() {
        assertTrue(AnswerMatcher.matchesFillBlank("display ip routing-table", Collections.singletonList("display ip routing-table")));
        assertFalse(AnswerMatcher.matchesFillBlank("display ip routing table", Collections.singletonList("display ip routing-table")));
    }
}
