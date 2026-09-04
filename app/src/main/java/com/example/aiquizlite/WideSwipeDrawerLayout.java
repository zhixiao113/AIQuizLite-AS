package com.example.aiquizlite;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

public class WideSwipeDrawerLayout extends DrawerLayout {
    private float gestureStartX;
    private float gestureStartY;
    private boolean wideSwipeCandidate;
    private boolean wideSwipeOpened;
    private final float minimumSwipeDistance;

    public WideSwipeDrawerLayout(@NonNull Context context) {
        this(context, null);
    }

    public WideSwipeDrawerLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WideSwipeDrawerLayout(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        minimumSwipeDistance = Math.max(
                ViewConfiguration.get(context).getScaledTouchSlop() * 2f,
                48f * density
        );
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        boolean interceptedByDrawer = super.onInterceptTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartX = event.getX();
                gestureStartY = event.getY();
                wideSwipeCandidate = !isDrawerOpen(GravityCompat.START)
                        && gestureStartX <= getWidth() * 0.5f;
                wideSwipeOpened = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (wideSwipeCandidate && SwipeGesturePolicy.shouldOpenDrawer(
                        getWidth(),
                        gestureStartX,
                        event.getX() - gestureStartX,
                        event.getY() - gestureStartY,
                        minimumSwipeDistance
                )) {
                    wideSwipeCandidate = false;
                    wideSwipeOpened = true;
                    openDrawer(GravityCompat.START);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                wideSwipeCandidate = false;
                break;
            default:
                break;
        }
        return interceptedByDrawer;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (wideSwipeOpened) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                wideSwipeOpened = false;
                performClick();
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                wideSwipeOpened = false;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
