package com.example.aiquizlite;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class QuestionBankActivity extends AppCompatActivity {
    private static final long DRAG_HOLD_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final List<QuestionRepository.QuestionBank> currentBanks = new ArrayList<>();

    private ScrollView scrollView;
    private LinearLayout cardsContainer;
    private String selectedQuestionBankId;
    private View draggingRow;
    private float dragStartRawY;
    private float dragStartElevation;
    private int dragFromIndex = -1;
    private int dragTargetIndex = -1;
    private boolean dragging;
    private int touchSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        selectedQuestionBankId = getIntent().getStringExtra(QuizActivity.EXTRA_QUESTION_BANK);
        if (selectedQuestionBankId == null) {
            selectedQuestionBankId = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .getString(MainActivity.KEY_SELECTED_BANK, null);
        }
        buildLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        QuestionRepository.resetCache();
        renderCards();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(56), dp(24), dp(20));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("总题库");
        title.setTextColor(getColor(R.color.brand_ink));
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton importButton = new MaterialButton(this);
        importButton.setText("导入");
        importButton.setIconResource(R.drawable.ic_import_question_bank);
        importButton.setIconPadding(dp(6));
        importButton.setTextSize(14);
        importButton.setOnClickListener(view -> startActivity(new Intent(this, ImportQuestionBankActivity.class)));
        header.addView(importButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView hint = new TextView(this);
        hint.setText("点击卡片切换题库；左滑删除；长按卡片并拖动可调整顺序。");
        hint.setTextColor(getColor(R.color.brand_text_secondary));
        hint.setTextSize(14);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hintParams.topMargin = dp(10);
        root.addView(hint, hintParams);

        scrollView = new ScrollView(this);
        scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        scrollParams.topMargin = dp(16);
        root.addView(scrollView, scrollParams);

        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(cardsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void renderCards() {
        currentBanks.clear();
        currentBanks.addAll(QuestionRepository.getQuestionBanks(this));
        QuestionRepository.QuestionBank selectedBank =
                QuestionRepository.findQuestionBank(this, selectedQuestionBankId);
        selectedQuestionBankId = selectedBank.getId();
        persistSelectedQuestionBankId();

        cardsContainer.removeAllViews();
        for (QuestionRepository.QuestionBank bank : currentBanks) {
            cardsContainer.addView(createBankRow(bank));
        }
    }

    private View createBankRow(QuestionRepository.QuestionBank bank) {
        FrameLayout row = new FrameLayout(this);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(12);
        row.setLayoutParams(rowParams);

        TextView deleteView = new TextView(this);
        deleteView.setText("删除");
        deleteView.setTextColor(Color.WHITE);
        deleteView.setTextSize(16);
        deleteView.setGravity(Gravity.CENTER);
        deleteView.setTypeface(deleteView.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable deleteBackground = new GradientDrawable();
        deleteBackground.setColor(getColor(R.color.brand_error));
        float radius = dp(8);
        deleteBackground.setCornerRadii(new float[]{0, 0, radius, radius, radius, radius, 0, 0});
        deleteView.setBackground(deleteBackground);
        FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(dp(88), dp(1));
        deleteParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        row.addView(deleteView, deleteParams);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.brand_card));
        card.setRadius(dp(8));
        card.setCardElevation(dp(2));
        card.setUseCompatPadding(false);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        row.addView(card, cardParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(content);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(bank.getDisplayName() + (bank.getId().equals(selectedQuestionBankId) ? "  ✓" : ""));
        name.setTextColor(getColor(R.color.brand_ink));
        name.setTextSize(17);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        textBox.addView(name);

        TextView detail = new TextView(this);
        detail.setText(buildBankDetail(bank));
        detail.setTextColor(getColor(R.color.brand_text_secondary));
        detail.setTextSize(14);
        detail.setPadding(0, dp(6), 0, 0);
        textBox.addView(detail);

        row.post(() -> syncDeleteHeight(row, card, deleteView));
        bindCardGestures(row, card, bank);
        deleteView.setOnClickListener(view -> confirmDelete(bank));
        return row;
    }

    private void syncDeleteHeight(FrameLayout row, View card, TextView deleteView) {
        int cardHeight = card.getHeight();
        if (cardHeight <= 0) {
            return;
        }
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        rowParams.height = cardHeight;
        row.setLayoutParams(rowParams);

        FrameLayout.LayoutParams deleteParams = (FrameLayout.LayoutParams) deleteView.getLayoutParams();
        deleteParams.height = cardHeight;
        deleteView.setLayoutParams(deleteParams);
    }

    private String buildBankDetail(QuestionRepository.QuestionBank bank) {
        String dateText = bank.isImported() && bank.getImportedAt() > 0
                ? dateFormat.format(new Date(bank.getImportedAt()))
                : "内置题库";
        return "导入日期：" + dateText + " · 总题数：" + bank.getQuestionCount();
    }

    private void bindCardGestures(FrameLayout row, MaterialCardView card, QuestionRepository.QuestionBank bank) {
        int deleteWidth = dp(88);
        float[] downX = new float[1];
        float[] downY = new float[1];
        float[] startTranslationX = new float[1];
        boolean[] longPressArmed = new boolean[1];
        boolean[] swiping = new boolean[1];

        Runnable startDrag = () -> {
            longPressArmed[0] = false;
            beginDrag(row);
        };

        card.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    startTranslationX[0] = view.getTranslationX();
                    swiping[0] = false;
                    longPressArmed[0] = true;
                    handler.postDelayed(startDrag, DRAG_HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    if (dragging && draggingRow == row) {
                        updateDrag(row, dy);
                        return true;
                    }

                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        if (Math.abs(dx) > Math.abs(dy)) {
                            swiping[0] = true;
                            cancelLongPress(startDrag, longPressArmed);
                            float nextTranslation = Math.max(-deleteWidth, Math.min(0, startTranslationX[0] + dx));
                            view.setTranslationX(nextTranslation);
                            return true;
                        }
                        if (Math.abs(dy) > touchSlop) {
                            cancelLongPress(startDrag, longPressArmed);
                        }
                    }

                    if (swiping[0]) {
                        float nextTranslation = Math.max(-deleteWidth, Math.min(0, startTranslationX[0] + dx));
                        view.setTranslationX(nextTranslation);
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelLongPress(startDrag, longPressArmed);
                    if (dragging && draggingRow == row) {
                        finishDrag(row);
                        return true;
                    }
                    if (swiping[0] || view.getTranslationX() != 0f) {
                        view.animate()
                                .translationX(view.getTranslationX() < -deleteWidth / 2f ? -deleteWidth : 0)
                                .setDuration(140)
                                .start();
                        return true;
                    }
                    if (Math.abs(event.getRawX() - downX[0]) < touchSlop
                            && Math.abs(event.getRawY() - downY[0]) < touchSlop) {
                        selectBank(bank);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void cancelLongPress(Runnable runnable, boolean[] longPressArmed) {
        if (!longPressArmed[0]) {
            return;
        }
        longPressArmed[0] = false;
        handler.removeCallbacks(runnable);
    }

    private void beginDrag(View row) {
        dragging = true;
        draggingRow = row;
        dragFromIndex = cardsContainer.indexOfChild(row);
        dragTargetIndex = dragFromIndex;
        dragStartRawY = 0f;
        dragStartElevation = row.getElevation();
        row.setPressed(true);
        row.setElevation(dp(12));
        row.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        row.animate().scaleX(1.02f).scaleY(1.02f).alpha(0.94f).setDuration(120).start();
        if (scrollView != null) {
            scrollView.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void updateDrag(View row, float dy) {
        if (dragStartRawY == 0f) {
            dragStartRawY = dy;
        }
        row.setTranslationY(dy - dragStartRawY);
        int newTargetIndex = findDropIndex(row);
        if (newTargetIndex != dragTargetIndex) {
            dragTargetIndex = newTargetIndex;
            applyDragOffsets(row);
        }
    }

    private void finishDrag(View row) {
        int fromIndex = dragFromIndex;
        int toIndex = dragTargetIndex;
        dragging = false;
        draggingRow = null;
        dragFromIndex = -1;
        dragTargetIndex = -1;
        row.setPressed(false);
        row.setElevation(dragStartElevation);
        row.setLayerType(View.LAYER_TYPE_NONE, null);

        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            QuestionRepository.QuestionBank moved = currentBanks.remove(fromIndex);
            currentBanks.add(toIndex, moved);
            saveCurrentOrder();
        }

        clearDragOffsets(row);
        row.animate()
                .translationY(0)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(160)
                .withEndAction(this::renderCards)
                .start();
        if (scrollView != null) {
            scrollView.requestDisallowInterceptTouchEvent(false);
        }
    }

    private void applyDragOffsets(View row) {
        int fromIndex = dragFromIndex;
        int targetIndex = dragTargetIndex;
        if (fromIndex < 0 || targetIndex < 0) {
            return;
        }
        int shift = row.getHeight() + dp(12);
        for (int i = 0; i < cardsContainer.getChildCount(); i++) {
            View child = cardsContainer.getChildAt(i);
            if (child == row) {
                continue;
            }
            float offset = 0f;
            if (targetIndex > fromIndex && i > fromIndex && i <= targetIndex) {
                offset = -shift;
            } else if (targetIndex < fromIndex && i >= targetIndex && i < fromIndex) {
                offset = shift;
            }
            child.animate().translationY(offset).setDuration(110).start();
        }
    }

    private void clearDragOffsets(View row) {
        for (int i = 0; i < cardsContainer.getChildCount(); i++) {
            View child = cardsContainer.getChildAt(i);
            if (child == row) {
                continue;
            }
            child.animate().translationY(0f).setDuration(120).start();
        }
    }

    private int findDropIndex(View row) {
        float movingCenter = row.getTop() + row.getTranslationY() + row.getHeight() / 2f;
        int targetIndex = cardsContainer.indexOfChild(row);
        for (int i = 0; i < cardsContainer.getChildCount(); i++) {
            View child = cardsContainer.getChildAt(i);
            if (child == row) {
                continue;
            }
            float childCenter = child.getTop() + child.getHeight() / 2f;
            if (movingCenter > childCenter) {
                targetIndex = i;
            } else if (movingCenter < childCenter && i < targetIndex) {
                targetIndex = i;
                break;
            }
        }
        return Math.max(0, Math.min(targetIndex, currentBanks.size() - 1));
    }

    private void saveCurrentOrder() {
        List<String> ids = new ArrayList<>();
        for (QuestionRepository.QuestionBank bank : currentBanks) {
            ids.add(bank.getId());
        }
        QuestionRepository.setQuestionBankOrder(this, ids);
    }

    private void selectBank(QuestionRepository.QuestionBank bank) {
        selectedQuestionBankId = bank.getId();
        persistSelectedQuestionBankId();
        Toast.makeText(this, "已选择：" + bank.getDisplayName(), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete(QuestionRepository.QuestionBank bank) {
        new AlertDialog.Builder(this)
                .setTitle("删除题库")
                .setMessage("确认删除“" + bank.getDisplayName() + "”吗？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean deleted = QuestionRepository.deleteQuestionBank(this, bank.getId());
                    if (!deleted) {
                        Toast.makeText(this, "至少需要保留一个题库", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (bank.getId().equals(selectedQuestionBankId)) {
                        selectedQuestionBankId = QuestionRepository.getDefaultQuestionBank(this).getId();
                        persistSelectedQuestionBankId();
                    }
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    renderCards();
                })
                .show();
    }

    private void persistSelectedQuestionBankId() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.KEY_SELECTED_BANK, selectedQuestionBankId)
                .apply();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
