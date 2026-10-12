package com.example.aiquizlite;

import android.content.Context;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Shared light touch feedback. Never changes or bypasses system touch settings. */
final class OperationFeedback {
    static boolean systemFeedbackEnabled(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static OperationFeedbackRules.Result perform(View source, GeneralSettingsStore settings) {
        if (source == null || settings == null) return OperationFeedbackRules.Result.UNAVAILABLE;
        return OperationFeedbackRules.perform(settings.shouldUseHapticFeedback(),
                systemFeedbackEnabled(source.getContext()),
                () -> source.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY));
    }

    private OperationFeedback() { }
}
