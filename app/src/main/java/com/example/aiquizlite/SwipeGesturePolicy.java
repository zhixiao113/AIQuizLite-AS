package com.example.aiquizlite;

final class SwipeGesturePolicy {
    private static final float MAX_START_FRACTION = 0.5f;
    private static final float HORIZONTAL_DOMINANCE = 1.2f;

    private SwipeGesturePolicy() {
    }

    static boolean shouldOpenDrawer(
            float containerWidth,
            float startX,
            float deltaX,
            float deltaY,
            float minimumDistance
    ) {
        return containerWidth > 0
                && startX >= 0
                && startX <= containerWidth * MAX_START_FRACTION
                && deltaX >= minimumDistance
                && deltaX > Math.abs(deltaY) * HORIZONTAL_DOMINANCE;
    }
}
