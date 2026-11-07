package com.example.aiquizlite;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiModelSwitchSettingTest {
    private final Map<String, Object> values = new HashMap<>();

    private GeneralSettingsStore store() {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putBoolean")) {
                        values.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if (method.getName().equals("apply")) return null;
                    throw new AssertionError(method.getName());
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().equals("getBoolean")) return values.getOrDefault(args[0], args[1]);
                    throw new AssertionError(method.getName());
                });
        return new GeneralSettingsStore(prefs);
    }

    @Test public void defaultOnSettingIsReadLiveAndDoesNotChangeOtherLearningPreferences() {
        values.put("show_source_explanation", false);
        GeneralSettingsStore openedDialog = store();
        assertTrue(openedDialog.isAiModelSwitchRegenerateEnabled());
        store().setAiModelSwitchRegenerateEnabled(false);
        assertFalse(openedDialog.isAiModelSwitchRegenerateEnabled());
        assertFalse(openedDialog.shouldShowSourceExplanation());
        store().setAiModelSwitchRegenerateEnabled(true);
        assertTrue(openedDialog.isAiModelSwitchRegenerateEnabled());
        assertEquals(2, values.size());
    }
}
