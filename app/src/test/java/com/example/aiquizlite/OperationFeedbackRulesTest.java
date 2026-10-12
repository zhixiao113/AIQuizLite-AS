package com.example.aiquizlite;

import org.junit.Test;
import static org.junit.Assert.*;

public class OperationFeedbackRulesTest {
    @Test public void disabledAppDoesNotCallTheDeviceEvenWithSystemEnabled() {
        assertEquals(OperationFeedbackRules.Result.DISABLED,
                OperationFeedbackRules.perform(false, true, () -> { fail("Disabled feedback played"); return true; }));
    }

    @Test public void disabledSystemMatchesReportedXiaomiCaseWithoutBypass() {
        assertEquals(OperationFeedbackRules.Result.SYSTEM_DISABLED,
                OperationFeedbackRules.perform(true, false, () -> { fail("System setting bypassed"); return true; }));
    }

    @Test public void enabledActionRequestsExactlyOneLightFeedback() {
        int[] calls = {0};
        assertEquals(OperationFeedbackRules.Result.REQUESTED,
                OperationFeedbackRules.perform(true, true, () -> { calls[0]++; return true; }));
        assertEquals(1, calls[0]);
    }

    @Test public void nativeRefusalIsReportedInsteadOfClaimingPhysicalVibration() {
        assertEquals(OperationFeedbackRules.Result.UNAVAILABLE,
                OperationFeedbackRules.perform(true, true, () -> false));
    }

    @Test public void unsupportedDeviceNeverBreaksSelectionOrSubmission() {
        assertEquals(OperationFeedbackRules.Result.UNAVAILABLE,
                OperationFeedbackRules.perform(true, true, () -> { throw new UnsupportedOperationException(); }));
    }

    @Test public void appSwitchCanChangeBetweenActionsWithoutRestart() {
        int[] calls = {0};
        assertEquals(OperationFeedbackRules.Result.REQUESTED,
                OperationFeedbackRules.perform(true, true, () -> { calls[0]++; return true; }));
        assertEquals(OperationFeedbackRules.Result.DISABLED,
                OperationFeedbackRules.perform(false, true, () -> { calls[0]++; return true; }));
        assertEquals(1, calls[0]);
    }

    @Test public void returningFromSystemSettingsRechecksTheGate() {
        assertEquals(OperationFeedbackRules.Result.SYSTEM_DISABLED,
                OperationFeedbackRules.perform(true, false, () -> true));
        assertEquals(OperationFeedbackRules.Result.REQUESTED,
                OperationFeedbackRules.perform(true, true, () -> true));
    }
}
