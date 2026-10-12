package com.example.aiquizlite;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ImageViewerHintStoreTest {
    private final Map<String, Object> persisted = new HashMap<>();
    private int writes;

    private ImageViewerHintStore viewer() {
        Map<String, Object> pending = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putBoolean")) {
                        pending.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if (method.getName().equals("apply")) {
                        persisted.putAll(pending);
                        pending.clear();
                        writes++;
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("edit")) { return editor; }
                    if (method.getName().equals("getBoolean")) {
                        return persisted.getOrDefault(args[0], args[1]);
                    }
                    throw new AssertionError(method.getName());
                });
        return new ImageViewerHintStore(preferences);
    }

    @Test public void firstSuccessfullyOpenedImageClaimsHintAndPersistsIt() {
        assertTrue(viewer().claimGestureHint());
        assertEquals(Boolean.TRUE, persisted.get("gesture_hint_shown"));
        assertEquals(1, writes);
    }

    @Test public void sameViewerCannotShowHintTwice() {
        ImageViewerHintStore viewer = viewer();
        assertTrue(viewer.claimGestureHint());
        assertFalse(viewer.claimGestureHint());
        assertEquals(1, writes);
    }

    @Test public void anotherImageOrBankSharesTheSameSeenState() {
        assertTrue(viewer().claimGestureHint());
        assertFalse(viewer().claimGestureHint());
        assertEquals(1, writes);
    }

    @Test public void recreatedViewerWithSavedSeenStateDoesNotShowHint() {
        persisted.put("gesture_hint_shown", true);
        assertFalse(viewer().claimGestureHint());
        assertEquals(0, writes);
    }

    @Test public void constructingStoreDoesNotConsumeHintForAnUnopenedImage() {
        viewer();
        assertTrue(persisted.isEmpty());
        assertEquals(0, writes);
        assertTrue(viewer().claimGestureHint());
    }

    @Test public void claimPreservesUnrelatedPreferences() {
        persisted.put("unrelated", false);
        assertTrue(viewer().claimGestureHint());
        assertEquals(Boolean.FALSE, persisted.get("unrelated"));
        assertEquals(2, persisted.size());
    }
}
