package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

/** App-wide onboarding, independent of a picture, question bank or learning-data backup. */
final class ImageViewerHintStore {
    private static final String PREF_NAME = "image_viewer_onboarding_v1";
    private static final String KEY_GESTURE_HINT_SHOWN = "gesture_hint_shown";
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    ImageViewerHintStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    ImageViewerHintStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    /** Call only after a picture has opened successfully, immediately before showing the hint. */
    boolean claimGestureHint() {
        synchronized (LOCK) {
            if (preferences.getBoolean(KEY_GESTURE_HINT_SHOWN, false)) {
                return false;
            }
            // apply updates the shared in-process state immediately and persists it asynchronously.
            preferences.edit().putBoolean(KEY_GESTURE_HINT_SHOWN, true).apply();
            return true;
        }
    }
}
