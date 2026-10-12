package com.example.aiquizlite;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class OperationFeedbackSettingsTest {
    private final Map<String, Object> values = new HashMap<>();

    private GeneralSettingsStore store() {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putBoolean")) {
                        values.put((String) args[0], args[1]); return proxy;
                    }
                    if (method.getName().equals("apply")) return null;
                    throw new AssertionError(method.getName());
                });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().equals("getBoolean")) return values.getOrDefault(args[0], args[1]);
                    throw new AssertionError(method.getName());
                });
        return new GeneralSettingsStore(preferences);
    }

    @Test public void originalDefaultRemainsEnabled() {
        assertTrue(store().shouldUseHapticFeedback());
    }

    @Test public void switchIsPersistedAndReadLiveWithoutRecreatingTheQuizStore() {
        GeneralSettingsStore quiz = store();
        store().setUseHapticFeedback(false);
        assertFalse(quiz.shouldUseHapticFeedback());
        assertFalse(store().shouldUseHapticFeedback());
        store().setUseHapticFeedback(true);
        assertTrue(quiz.shouldUseHapticFeedback());
    }

    @Test public void changingFeedbackLeavesOtherPreferencesUntouched() {
        values.put("resume_daily", false);
        values.put("question_font_mode", 2);
        values.put("image_fullscreen", false);
        Map<String, Object> original = new HashMap<>(values);
        store().setUseHapticFeedback(false);
        assertEquals(Boolean.FALSE, values.remove("haptic_feedback"));
        assertEquals(original, values);
    }
}
