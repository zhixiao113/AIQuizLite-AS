package com.example.aiquizlite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** A paged question grid in a modal bottom sheet. Question indices are session-relative. */
final class QuestionJumpSheet extends BottomSheetDialog {
    private static final int PAGE_SIZE = 20;
    private static final int COLUMNS = 5;

    private final int currentIndex;
    private final int total;
    private final IntConsumer onSelect;
    private final IntFunction<ProgressStore.AnswerState> answerState;
    private final IntFunction<String> labelProvider;
    private final LinearLayout grid;
    private final TextView range;
    private final MaterialButton previous;
    private final MaterialButton next;
    private int page;

    QuestionJumpSheet(Context context, int currentIndex, int total,
                      IntFunction<ProgressStore.AnswerState> answerState, IntConsumer onSelect) {
        this(context, currentIndex, total, answerState, onSelect, null);
    }

    QuestionJumpSheet(Context context, int currentIndex, int total,
                      IntFunction<ProgressStore.AnswerState> answerState, IntConsumer onSelect,
                      IntFunction<String> labelProvider) {
        super(context, R.style.ThemeOverlay_QuestionJumpSheet);
        this.currentIndex = currentIndex;
        this.total = total;
        this.onSelect = onSelect;
        this.answerState = answerState;
        this.labelProvider = labelProvider;
        page = currentIndex / PAGE_SIZE;

        setContentView(R.layout.dialog_question_jump);
        View content = findViewById(R.id.questionJumpContent);
        ViewCompat.setAccessibilityPaneTitle(content, context.getString(R.string.question_jump_title));
        ViewCompat.setAccessibilityHeading(content.findViewById(R.id.questionJumpTitle), true);
        TextView subtitle = content.findViewById(R.id.questionJumpSubtitle);
        TextView hint = content.findViewById(R.id.questionJumpHint);
        hint.setText(answerState == null ? R.string.question_jump_hint : R.string.question_jump_status_hint);
        subtitle.setText(context.getString(R.string.question_jump_subtitle, currentIndex + 1, total));
        content.findViewById(R.id.questionJumpClose).setOnClickListener(view -> dismiss());
        grid = content.findViewById(R.id.questionJumpGrid);
        range = content.findViewById(R.id.questionJumpRange);
        previous = content.findViewById(R.id.questionJumpPrevious);
        next = content.findViewById(R.id.questionJumpNext);
        previous.setOnClickListener(view -> {
            if (page > 0) {
                page--;
                renderPage();
            }
        });
        next.setOnClickListener(view -> {
            if ((page + 1) * PAGE_SIZE < total) {
                page++;
                renderPage();
            }
        });
        setCanceledOnTouchOutside(true);
        getBehavior().setSkipCollapsed(true);
        getBehavior().setShouldRemoveExpandedCorners(false);
        getBehavior().setMaxWidth(dp(600));
        // Short screens / large fonts can scroll the contents without clipping controls.
        getBehavior().setMaxHeight((int) (context.getResources().getDisplayMetrics().heightPixels * 0.9f));
        setOnShowListener(dialog -> getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED));
        renderPage();
    }

    private void renderPage() {
        grid.removeAllViews();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);
        // Keep four rows on the final page so the sheet and paging buttons do not jump.
        for (int row = 0; row < PAGE_SIZE / COLUMNS; row++) {
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            if (row > 0) {
                rowParams.topMargin = dp(10);
            }
            grid.addView(rowView, rowParams);
            for (int column = 0; column < COLUMNS; column++) {
                int index = start + row * COLUMNS + column;
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (column > 0) {
                    cellParams.setMarginStart(dp(8));
                }
                if (index >= end) {
                    View spacer = new View(getContext());
                    spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    rowView.addView(spacer, cellParams);
                    continue;
                }
                rowView.addView(createQuestionButton(index), cellParams);
            }
        }
        range.setText(getContext().getString(R.string.question_jump_range, start + 1, end, total));
        previous.setEnabled(page > 0);
        next.setEnabled(end < total);
    }

    private String questionLabel(int index) {
        if (labelProvider != null) {
            String label = labelProvider.apply(index);
            if (label != null && !label.isEmpty()) {
                return label;
            }
        }
        return String.valueOf(index + 1);
    }

    private MaterialButton createQuestionButton(int index) {
        boolean current = index == currentIndex;
        ProgressStore.AnswerState state = answerState == null ? null : answerState.apply(index);
        boolean answered = state != null && state.isAnswered();
        int background = current ? R.color.brand_primary : !answered ? R.color.brand_card
                : state.isCorrect() ? R.color.question_jump_correct : R.color.question_jump_wrong;
        String label = questionLabel(index);
        MaterialButton button = new MaterialButton(getContext());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button, 12, 17, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setMaxLines(1);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(10));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(ColorStateList.valueOf(getContext().getColor(background)));
        button.setTextColor(getContext().getColor(current || answered ? R.color.brand_card : R.color.brand_ink));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(getContext().getColor(
                current || answered ? background : R.color.brand_border)));
        button.setSelected(current);
        button.setContentDescription(getContext().getString(
                current ? R.string.question_jump_current : !answered ? R.string.question_jump_target
                        : state.isCorrect() ? R.string.question_jump_correct : R.string.question_jump_wrong,
                label));
        button.setOnClickListener(view -> {
            dismiss();
            onSelect.accept(index);
        });
        return button;
    }

    private int dp(int value) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * value);
    }
}
