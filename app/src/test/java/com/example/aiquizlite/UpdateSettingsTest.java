package com.example.aiquizlite;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateSettingsTest {
    private final Map<String,Object> values = new HashMap<>();
    private GeneralSettingsStore store() {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy,method,args) -> {
                    if (method.getName().startsWith("put")) { values.put((String)args[0],args[1]); return proxy; }
                    if (method.getName().equals("apply")) { return null; }
                    throw new AssertionError(method.getName());
                });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy,method,args) -> {
                    if (method.getName().equals("edit")) { return editor; }
                    if (method.getName().equals("getString") || method.getName().equals("getBoolean")) {
                        return values.getOrDefault(args[0],args[1]);
                    }
                    throw new AssertionError(method.getName());
                });
        return new GeneralSettingsStore(preferences);
    }
    @Test public void oldPreferenceIsPreservedButGithubBecomesDefault() {
        values.put("update_server_url",UpdateSourceRules.LEGACY_SERVER);
        GeneralSettingsStore store=store();
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store.getUpdateServerUrl());
        assertEquals(UpdateSourceRules.LEGACY_SERVER,values.get("update_server_url"));
    }
    @Test public void previouslySelectedLocalRootCannotRedirectUserUpdates() {
        values.put("local_update_source",true);
        values.put("update_server_url","http://192.168.1.2:4387/");
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store().getUpdateServerUrl());
    }
    @Test public void ignoringLegacyConfigurationDoesNotEraseLearningSettings() {
        values.put("resume_daily",false);
        values.put("local_update_source",true);
        values.put("update_server_url","https://example.org/update");
        Map<String,Object> original = new HashMap<>(values);
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store().getUpdateServerUrl());
        assertEquals(original,values);
    }
    @Test public void invalidLegacyAddressDoesNotBreakUserUpdates() {
        values.put("local_update_source",true);
        values.put("update_server_url","http://public.example.org");
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store().getUpdateServerUrl());
    }
    @Test public void restoredLegacyBackupStillUsesGithub() {
        GeneralSettingsStore existing = store();
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,existing.getUpdateServerUrl());
        // Backup restoration can replace preferences after the store was created.
        values.put("local_update_source",true);
        values.put("update_server_url",UpdateSourceRules.LEGACY_SERVER);
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,existing.getUpdateServerUrl());
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store().getUpdateServerUrl());
    }
}
