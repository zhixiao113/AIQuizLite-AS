package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

public final class MockExamStore {
    private static final String PREF_NAME = "mock_exam_attempts";
    private static final String ATTEMPT_PREFIX = "attempt_";
    private final SharedPreferences preferences;

    public MockExamStore(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void save(MockExamAttempt attempt) {
        preferences.edit()
                .putString(ATTEMPT_PREFIX + attempt.getId(), attempt.toJson())
                .commit();
    }

    public MockExamAttempt load(String attemptId) {
        if (attemptId == null || attemptId.trim().isEmpty()) {
            return null;
        }
        return MockExamAttempt.fromJson(preferences.getString(
                ATTEMPT_PREFIX + attemptId,
                ""
        ));
    }
}
