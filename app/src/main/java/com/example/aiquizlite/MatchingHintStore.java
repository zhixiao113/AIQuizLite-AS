package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

/** A single connection-tip reminder across matching questions and workspaces. */
final class MatchingHintStore {
    private static final String PREF_NAME = "matching_onboarding_v1";
    private static final String KEY_CONNECTION_HINT_SHOWN = "connection_hint_shown";
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    MatchingHintStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    MatchingHintStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    boolean claimConnectionHint() {
        synchronized (LOCK) {
            if (preferences.getBoolean(KEY_CONNECTION_HINT_SHOWN, false)) {
                return false;
            }
            preferences.edit().putBoolean(KEY_CONNECTION_HINT_SHOWN, true).apply();
            return true;
        }
    }
}
