package com.example.aiquizlite;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.google.android.material.card.MaterialCardView;

/** Observes content taps while leaving scrolling to the explanation ScrollView. */
public final class AiExplanationCard extends MaterialCardView {
    private Runnable onContentTap;
    private float downX;
    private float downY;
    private boolean dragged;
    private final int touchSlop;

    public AiExplanationCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setOnContentTap(Runnable listener) {
        onContentTap = listener;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            dragged = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            dragged |= Math.abs(event.getX() - downX) > touchSlop || Math.abs(event.getY() - downY) > touchSlop;
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragged
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()
                && onContentTap != null) {
            onContentTap.run();
        }
        return super.dispatchTouchEvent(event);
    }
}
