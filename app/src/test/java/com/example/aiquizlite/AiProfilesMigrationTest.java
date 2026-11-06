package com.example.aiquizlite;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class AiProfilesMigrationTest {
    private static final class Preferences {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "contains": return values.containsKey(args[0]);
                        case "edit": return editor();
                        case "getBoolean": case "getString": case "getLong": case "getStringSet":
                            return values.getOrDefault(args[0], args[1]);
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });

        private SharedPreferences.Editor editor() {
            Map<String, Object> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) { pending.put((String) args[0], args[1]); return proxy; }
                        if ("remove".equals(name)) { pending.put((String) args[0], null); return proxy; }
                        if ("apply".equals(name)) {
                            pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                            return null;
                        }
                        throw new UnsupportedOperationException(name);
                    });
        }
    }

    @Test public void legacyProfilesKeepOrderEnabledAndCiphertextWithoutAssumingVision() {
        Preferences memory = new Preferences();
        memory.values.put("profiles", new HashSet<>(Arrays.asList("beta", "empty", "alpha")));
        memory.values.put("model", "deepseek-chat");
        memory.values.put("base_url", "https://example.com");
        memory.values.put("alpha:model", "gpt-4o");
        memory.values.put("beta:model", "qwen-vl-plus");
        memory.values.put("beta:encrypted_api_key", "legacy-ciphertext");
        memory.values.put("beta:api_key_iv", "legacy-iv");
        memory.values.put("enabled_alpha", false);
        memory.values.put("role_vision", "beta");
        AiProfiles profiles = new AiProfiles(memory.prefs);
        List<AiProfiles.Model> models = profiles.models();
        assertEquals(Arrays.asList("default", "alpha", "beta"), models.stream().map(m -> m.id).collect(Collectors.toList()));
        assertFalse(profiles.model("alpha").enabled);
        assertFalse(profiles.model("beta").visionTested);
        assertEquals("legacy-ciphertext", memory.values.get("beta:encrypted_api_key"));
        assertEquals("legacy-iv", memory.values.get("beta:api_key_iv"));
        profiles.delete("default");
        assertNull(new AiProfiles(memory.prefs).model("default"));
        assertFalse(memory.values.containsKey("model"));
    }

    @Test public void cooldownPersistsAndStaleAttemptsCannotCoolChangedState() {
        Preferences memory = new Preferences();
        memory.values.put("model", "model");
        AiProfiles profiles = new AiProfiles(memory.prefs);
        AiProfiles.Model before = profiles.model("default");
        assertTrue(profiles.enable("default", false));
        assertTrue(profiles.enable("default", true));
        long now = System.currentTimeMillis();
        assertFalse(profiles.markFailed(before, now));
        AiProfiles.Model active = profiles.model("default");
        assertTrue(profiles.markFailed(active, now));
        AiProfiles reopened = new AiProfiles(memory.prefs);
        AiProfiles.Model cooled = reopened.model("default");
        assertTrue(cooled.enabled);
        assertFalse(reopened.enable("default", true));
        assertTrue(cooled.isCooling(now + AiProfiles.COOLDOWN_MILLIS - 1));
        assertFalse(cooled.isCooling(now + AiProfiles.COOLDOWN_MILLIS));
        reopened.delete("default");
        assertFalse(profiles.markFailed(active, now));
    }
}
