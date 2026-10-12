package com.example.aiquizlite;

import java.util.function.BooleanSupplier;

/** A request result is not proof that a physical motor was felt by the user. */
final class OperationFeedbackRules {
    enum Result { DISABLED, SYSTEM_DISABLED, REQUESTED, UNAVAILABLE }

    static Result perform(boolean enabled, boolean systemEnabled, BooleanSupplier request) {
        if (!enabled) return Result.DISABLED;
        if (!systemEnabled) return Result.SYSTEM_DISABLED;
        try {
            return request.getAsBoolean() ? Result.REQUESTED : Result.UNAVAILABLE;
        } catch (RuntimeException unsupported) {
            // Optional feedback must never interrupt an answer, favorite or submission.
            return Result.UNAVAILABLE;
        }
    }

    private OperationFeedbackRules() { }
}
