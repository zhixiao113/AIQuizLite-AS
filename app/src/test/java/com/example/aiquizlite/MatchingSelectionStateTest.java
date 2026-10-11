package com.example.aiquizlite;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MatchingSelectionStateTest {
    private MatchingData data(String mode) {
        return new MatchingData(mode,
                Arrays.asList(new MatchingData.Item("a", "A"), new MatchingData.Item("b", "B")),
                Arrays.asList(new MatchingData.Item("x", "X"), new MatchingData.Item("y", "Y")),
                Arrays.asList(new MatchingData.Pair("a", "x"), new MatchingData.Pair("b", "y")));
    }
    @Test public void twoEditorsShareHistoryAcrossConnectRemoveClearAndUndo() {
        MatchingSelectionState compact = new MatchingSelectionState(data(MatchingData.LEFT_REUSABLE));
        final int[] notifications = {0};
        Runnable listener = () -> notifications[0]++;
        compact.addListener(listener);
        compact.connect("a", "x");
        MatchingSelectionState expanded = compact; // Both view constructors receive this same object.
        expanded.connect("a", "y");
        compact.undo();
        assertNull(expanded.sourceFor("y"));
        assertEquals("a", expanded.sourceFor("x"));
        expanded.remove("x");
        compact.undo();
        assertEquals("a", expanded.sourceFor("x"));
        expanded.clear(); compact.undo(); compact.undo();
        assertEquals(0, expanded.size());
        assertFalse(compact.canUndo());
        assertEquals(8, notifications[0]);
        compact.removeListener(listener); compact.connect("a", "y");
        assertEquals(8, notifications[0]);
    }
    @Test public void invalidOrRepeatedConnectionsDoNotCreateUndoSteps() {
        MatchingSelectionState state = new MatchingSelectionState(data(MatchingData.ONE_TO_ONE));
        assertTrue(state.connect("a", "x"));
        assertTrue(state.connect("a", "x"));
        assertFalse(state.connect("a", "y"));
        assertFalse(state.connect("unknown", "y"));
        state.undo();
        assertEquals(0, state.size());
        assertFalse(state.canUndo());
    }
    @Test public void restoringDraftResetsHistoryAndCanThenBeEditedAndUndone() {
        MatchingSelectionState state = new MatchingSelectionState(data(MatchingData.LEFT_REUSABLE));
        state.connect("a", "x");
        state.restore(Collections.singletonList(new MatchingData.Pair("b", "y")));
        assertFalse(state.canUndo());
        state.connect("a", "y"); state.undo();
        assertEquals("b", state.sourceFor("y"));
    }
}
