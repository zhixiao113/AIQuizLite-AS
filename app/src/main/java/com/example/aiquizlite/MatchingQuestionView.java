package com.example.aiquizlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Two-column, touch-and-tap matching editor used by quiz modes. */
public final class MatchingQuestionView extends FrameLayout {
    private static final int BLUE = Color.rgb(46, 111, 231);
    private static final int GREEN = Color.rgb(22, 139, 94);
    private static final int RED = Color.rgb(214, 67, 65);
    private final MatchingData data;
    private final Map<String, View> leftNodes = new HashMap<>();
    private final Map<String, View> rightNodes = new HashMap<>();
    private final Map<String, View> leftCards = new HashMap<>();
    private final Map<String, View> rightCards = new HashMap<>();
    private final MatchingSelectionState selection;
    private final Runnable selectionListener = this::refreshSelection;
    private final LineLayer lines;
    private Runnable onChanged;
    private boolean editable = true;
    private boolean submitted;
    private boolean showCorrect;
    private String pendingSide;
    private String pendingId;
    private String dragSide;
    private String dragId;
    private float dragX;
    private float dragY;
    private float downX;
    private float downY;
    private final int[] screenPosition = new int[2];
    private final int[] ownPosition = new int[2];
    private final Runnable scrollFrame = new Runnable() {
        @Override public void run() {
            if (dragId == null || !editable) return;
            autoScroll(dragY);
            updateHighlights();
            lines.invalidate();
            postOnAnimation(this);
        }
    };

    public MatchingQuestionView(Context context, MatchingData data, int textSp) {
        this(context, data, textSp, new MatchingSelectionState(data));
    }

    MatchingQuestionView(Context context, MatchingData data, int textSp,
                         MatchingSelectionState selection) {
        super(context);
        this.data = data;
        this.selection = selection;
        setClipChildren(false);
        lines = new LineLayer(context);
        addView(lines, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setClipChildren(false);
        LinearLayout left = column(context, data.getLeftItems(), "left", textSp);
        LinearLayout right = column(context, data.getRightItems(), "right", textSp);
        columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        columns.addView(new View(context), new LinearLayout.LayoutParams(dp(56), 1));
        columns.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(columns, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setOnSelectionChangedListener(Runnable listener) { onChanged = listener; }
    MatchingSelectionState getSelectionState() { return selection; }
    public boolean canUndo() { return selection.canUndo(); }
    public boolean isDragging() { return dragId != null; }
    public boolean isSubmitted() { return submitted; }
    public boolean isEditable() { return editable; }
    public boolean isComplete() { return data.isComplete(getSelectedPairs()); }
    public int getCompletedCount() { return selection.size(); }
    public int getTargetCount() { return data.getRightItems().size(); }

    public List<MatchingData.Pair> getSelectedPairs() {
        return selection.pairs();
    }

    public void setSelectedPairs(List<MatchingData.Pair> pairs) {
        selection.restore(pairs);
        if (!isAttachedToWindow()) refreshSelection();
    }

    public void setSubmitted(boolean value) {
        submitted = value;
        editable = !value;
        if (!value) showCorrect = false;
        pendingSide = null;
        pendingId = null;
        cancelDrag();
        refreshSelection();
    }

    public void setShowCorrect(boolean value) {
        showCorrect = value && submitted;
        lines.invalidate();
    }

    public void setEditable(boolean value) {
        editable = value && !submitted;
        if (!editable) cancelDrag();
    }

    public void undo() {
        if (editable) selection.undo();
    }

    public void clear() {
        if (editable) selection.clear();
    }

    private LinearLayout column(Context context, List<MatchingData.Item> items,
                                String side, int textSp) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) {
            MatchingData.Item item = items.get(i);
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(dp(78));
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(248, 249, 251));
            background.setCornerRadius(dp(10));
            background.setStroke(dp(1), Color.rgb(222, 226, 232));
            card.setBackground(background);
            if ("right".equals(side)) {
                card.setOnClickListener(view -> {
                    if (!editable || selection.sourceFor(item.getId()) == null) return;
                    String source = selection.sourceFor(item.getId());
                    new AlertDialog.Builder(getContext())
                            .setMessage("当前连接：" + data.rightText(item.getId()) + " → "
                                    + data.leftText(source))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("移除连接", (dialog, which) -> {
                                if (editable) selection.remove(item.getId());
                            }).show();
                });
            }

            TextView label = new TextView(context);
            label.setText((i + 1) + "、" + item.getText());
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
            label.setTextColor(Color.rgb(32, 40, 54));
            label.setLineSpacing(dp(2), 1f);
            TextView node = new TextView(context);
            node.setText("●");
            node.setGravity(Gravity.CENTER);
            node.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            node.setTextColor(BLUE);
            node.setContentDescription(("left".equals(side) ? "左侧" : "右侧")
                    + "连接点：" + item.getText());
            node.setOnClickListener(view -> {
                if (editable) handleTap(side, item.getId());
                updateHighlights();
            });
            node.setOnTouchListener((view, event) -> {
                if (editable && event.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.hypot(event.getRawX() - downX, event.getRawY() - downY) < dp(14)) {
                    view.performClick();
                    cancelDrag();
                    return true;
                }
                return onNodeTouch(side, item.getId(), event);
            });
            if ("left".equals(side)) {
                card.addView(label, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                card.addView(node, new LinearLayout.LayoutParams(dp(44), dp(48)));
                leftNodes.put(item.getId(), node);
                leftCards.put(item.getId(), card);
            } else {
                card.addView(node, new LinearLayout.LayoutParams(dp(44), dp(48)));
                card.addView(label, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                rightNodes.put(item.getId(), node);
                rightCards.put(item.getId(), card);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            column.addView(card, params);
        }
        return column;
    }

    private boolean onNodeTouch(String side, String id, MotionEvent event) {
        if (!editable) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragSide = side;
                dragId = id;
                downX = event.getRawX();
                downY = event.getRawY();
                dragX = downX;
                dragY = downY;
                getParent().requestDisallowInterceptTouchEvent(true);
                removeCallbacks(scrollFrame);
                postOnAnimation(scrollFrame);
                updateHighlights();
                lines.invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                dragX = event.getRawX();
                dragY = event.getRawY();
                updateHighlights();
                lines.invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                String target = findTarget("left".equals(side) ? rightCards : leftCards,
                        event.getRawX(), event.getRawY());
                if (target != null) {
                    if ("left".equals(side)) connect(id, target);
                    else connect(target, id);
                }
                cancelDrag();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelDrag();
                return true;
            default:
                return true;
        }
    }

    private void handleTap(String side, String id) {
        if (pendingId == null || side.equals(pendingSide)) {
            pendingSide = side;
            pendingId = id;
            Toast.makeText(getContext(), "请点击另一侧项目的连接点", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("left".equals(side)) connect(id, pendingId);
        else connect(pendingId, id);
        pendingSide = null;
        pendingId = null;
    }

    private void connect(String leftId, String rightId) {
        if (!selection.connect(leftId, rightId)) {
            Toast.makeText(getContext(), "此项只能使用一次，请先移除原连接", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSelection() {
        pendingSide = null;
        pendingId = null;
        updateHighlights();
        lines.invalidate();
        if (onChanged != null) onChanged.run();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        selection.addListener(selectionListener);
        refreshSelection();
    }

    @Override protected void onDetachedFromWindow() {
        selection.removeListener(selectionListener);
        cancelDrag();
        super.onDetachedFromWindow();
    }

    private void updateHighlights() {
        String activeSide = dragId == null ? pendingSide : dragSide;
        String activeId = dragId == null ? pendingId : dragId;
        String hovered = dragId == null ? null : findTarget(
                "left".equals(dragSide) ? rightCards : leftCards, dragX, dragY);
        highlightCards(leftCards, "left", activeSide, activeId, hovered);
        highlightCards(rightCards, "right", activeSide, activeId, hovered);
    }

    private void highlightCards(Map<String, View> cards, String side,
                                String activeSide, String activeId, String hovered) {
        for (Map.Entry<String, View> entry : cards.entrySet()) {
            boolean active = editable && (side.equals(activeSide)
                    ? entry.getKey().equals(activeId) : entry.getKey().equals(hovered));
            GradientDrawable background = (GradientDrawable) entry.getValue().getBackground();
            background.setColor(active ? Color.rgb(232, 240, 255) : Color.rgb(248, 249, 251));
            background.setStroke(dp(active ? 2 : 1), active ? BLUE : Color.rgb(222, 226, 232));
        }
    }

    private void cancelDrag() {
        removeCallbacks(scrollFrame);
        dragSide = null;
        dragId = null;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        updateHighlights();
        lines.invalidate();
    }

    private String findTarget(Map<String, View> cards, float rawX, float rawY) {
        Rect rect = new Rect();
        for (Map.Entry<String, View> entry : cards.entrySet()) {
            if (entry.getValue().getGlobalVisibleRect(rect) && rect.contains((int) rawX, (int) rawY)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void autoScroll(float rawY) {
        View parent = getParent() instanceof View ? (View) getParent() : null;
        while (parent != null && !(parent instanceof ScrollView)) {
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        if (!(parent instanceof ScrollView)) return;
        Rect viewport = new Rect();
        if (!parent.getGlobalVisibleRect(viewport)) return;
        if (rawY < viewport.top + dp(48)) ((ScrollView) parent).scrollBy(0, -dp(12));
        else if (rawY > viewport.bottom - dp(48)) ((ScrollView) parent).scrollBy(0, dp(12));
    }

    private void center(View view, float[] result) {
        view.getLocationOnScreen(screenPosition);
        getLocationOnScreen(ownPosition);
        result[0] = screenPosition[0] - ownPosition[0] + view.getWidth() / 2f;
        result[1] = screenPosition[1] - ownPosition[1] + view.getHeight() / 2f;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class LineLayer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] start = new float[2];
        private final float[] end = new float[2];
        private final int[] own = new int[2];
        private final DashPathEffect dragDash = new DashPathEffect(new float[]{dp(7), dp(5)}, 0);

        LineLayer(Context context) {
            super(context);
            setClickable(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<MatchingData.Pair> pairs = showCorrect
                    ? data.getCorrectPairs() : getSelectedPairs();
            for (MatchingData.Pair pair : pairs) {
                View left = leftNodes.get(pair.getLeftId());
                View right = rightNodes.get(pair.getRightId());
                if (left == null || right == null) continue;
                paint.setColor(!submitted ? BLUE : showCorrect
                        || data.getCorrectPairs().contains(pair) ? GREEN : RED);
                paint.setStrokeWidth(dp(3));
                paint.setPathEffect(null);
                center(left, start);
                center(right, end);
                canvas.drawLine(start[0], start[1], end[0], end[1], paint);
            }
            if (dragId != null) {
                View startView = "left".equals(dragSide)
                        ? leftNodes.get(dragId) : rightNodes.get(dragId);
                if (startView != null) {
                    center(startView, start);
                    getLocationOnScreen(own);
                    paint.setColor(BLUE);
                    paint.setStrokeWidth(dp(2));
                    paint.setPathEffect(dragDash);
                    canvas.drawLine(start[0], start[1], dragX - own[0], dragY - own[1], paint);
                    paint.setPathEffect(null);
                }
            }
        }
    }
}
