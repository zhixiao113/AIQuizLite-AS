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
        assertEquals(UpdateSourceRules.LEGACY_SERVER,store.getLocalUpdateServerUrl());
    }
    @Test public void explicitlySelectedLocalRootPersists() {
        store().setUpdateServerUrl("http://192.168.1.2:4387/");
        assertEquals("http://192.168.1.2:4387",store().getUpdateServerUrl());
    }
    @Test public void resetGithubDoesNotEraseLocalAddressOrLearningSettings() {
        values.put("resume_daily",false);
        store().setUpdateServerUrl("https://example.org/update");
        store().setUpdateServerUrl(UpdateSourceRules.GITHUB_MANIFEST);
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store().getUpdateServerUrl());
        assertEquals("https://example.org/update",store().getLocalUpdateServerUrl());
        assertEquals(false,values.get("resume_daily"));
    }
    @Test public void rejectedAddressDoesNotChangeSource() {
        assertThrows(IllegalArgumentException.class, () -> store().setUpdateServerUrl("http://public.example.org"));
        assertEquals(UpdateSourceRules.GITHUB_MANIFEST,store().getUpdateServerUrl());
    }
}
