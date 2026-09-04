package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwipeGesturePolicyTest {
    private static final float WIDTH = 1000f;
    private static final float MINIMUM_DISTANCE = 100f;

    @Test
    public void opensForHorizontalRightSwipeStartingInLeftHalf() {
        assertTrue(SwipeGesturePolicy.shouldOpenDrawer(
                WIDTH, 490f, 180f, 30f, MINIMUM_DISTANCE
        ));
    }

    @Test
    public void rejectsSwipeStartingPastScreenMiddle() {
        assertFalse(SwipeGesturePolicy.shouldOpenDrawer(
                WIDTH, 510f, 180f, 20f, MINIMUM_DISTANCE
        ));
    }

    @Test
    public void rejectsVerticalScrollAndLeftSwipe() {
        assertFalse(SwipeGesturePolicy.shouldOpenDrawer(
                WIDTH, 200f, 120f, 180f, MINIMUM_DISTANCE
        ));
        assertFalse(SwipeGesturePolicy.shouldOpenDrawer(
                WIDTH, 200f, -180f, 10f, MINIMUM_DISTANCE
        ));
    }

    @Test
    public void rejectsShortHorizontalMovement() {
        assertFalse(SwipeGesturePolicy.shouldOpenDrawer(
                WIDTH, 200f, 99f, 5f, MINIMUM_DISTANCE
        ));
    }
}
