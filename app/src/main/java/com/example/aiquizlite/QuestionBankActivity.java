package com.example.aiquizlite;

import android.Manifest;
import android.content.ClipData;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
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
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuestionBankActivity extends AppCompatActivity {
    private static final long DRAG_HOLD_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final List<QuestionRepository.QuestionBank> currentBanks = new ArrayList<>();
    private final ExecutorService packageExecutor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Intent> libraryImportLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::handleLibraryImportResult
            );
    private final ActivityResultLauncher<Intent> materialImportLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::handleMaterialImportResult
            );
    private final ActivityResultLauncher<String> storagePermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    this::handleStoragePermissionResult
            );
    private final ActivityResultLauncher<String> saveZipLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/zip"),
                    this::handleSaveDestination
            );

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
    private boolean packageOperationRunning;
    private QuestionBankPackageManager.ExportedPackage pendingExport;
    private QuestionRepository.QuestionBank pendingPermissionBank;

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

        LinearLayout importToolbar = new LinearLayout(this);
        importToolbar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        toolbarParams.topMargin = dp(14);
        root.addView(importToolbar, toolbarParams);

        MaterialButton jsonImportButton = new MaterialButton(this);
        jsonImportButton.setText("＋ 资料");
        jsonImportButton.setTextSize(14);
        jsonImportButton.setTextColor(getColor(R.color.brand_primary));
        jsonImportButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_card)));
        jsonImportButton.setStrokeColor(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        jsonImportButton.setStrokeWidth(dp(1));
        jsonImportButton.setOnClickListener(view -> materialImportLauncher.launch(
                new Intent(this, MaterialToQuizActivity.class)
        ));
        LinearLayout.LayoutParams importButtonParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        importButtonParams.rightMargin = dp(8);
        importToolbar.addView(jsonImportButton, importButtonParams);

        MaterialButton zipImportButton = new MaterialButton(this);
        zipImportButton.setText("＋ ZIP 题库包");
        zipImportButton.setTextSize(14);
        zipImportButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("导入 ZIP 题库包")
                .setItems(new String[]{"选择本地文件", "从下载库导入"}, (dialog, which) -> {
                    if (which == 0) {
                        libraryImportLauncher.launch(new Intent(this, ImportQuestionBankActivity.class));
                    } else {
                        openDownloadLibraryForImport();
                    }
                })
                .show());
        importToolbar.addView(zipImportButton, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView hint = new TextView(this);
        hint.setText("点击卡片切换题库；左滑可编辑、导出或删除；长按拖动可调整顺序。");
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams actionsParams = new FrameLayout.LayoutParams(dp(216), dp(1));
        actionsParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        row.addView(actions, actionsParams);

        TextView editView = new TextView(this);
        editView.setText("编辑");
        editView.setTextColor(Color.WHITE);
        editView.setTextSize(16);
        editView.setGravity(Gravity.CENTER);
        editView.setTypeface(editView.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable editBackground = new GradientDrawable();
        editBackground.setColor(getColor(R.color.brand_primary));
        editView.setBackground(editBackground);
        actions.addView(editView, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView exportView = new TextView(this);
        exportView.setText("导出");
        exportView.setTextColor(Color.WHITE);
        exportView.setTextSize(16);
        exportView.setGravity(Gravity.CENTER);
        exportView.setTypeface(exportView.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable exportBackground = new GradientDrawable();
        exportBackground.setColor(getColor(R.color.brand_success));
        exportView.setBackground(exportBackground);
        actions.addView(exportView, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));

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
        actions.addView(deleteView, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));

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

        row.post(() -> syncActionHeight(row, card, actions));
        bindCardGestures(row, card, bank);
        editView.setOnClickListener(view -> editBank(bank));
        exportView.setOnClickListener(view -> startAutomaticExport(bank));
        deleteView.setOnClickListener(view -> confirmDelete(bank));
        return row;
    }

    private void syncActionHeight(FrameLayout row, View card, View actions) {
        int cardHeight = card.getHeight();
        if (cardHeight <= 0) {
            return;
        }
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        rowParams.height = cardHeight;
        row.setLayoutParams(rowParams);

        FrameLayout.LayoutParams actionParams = (FrameLayout.LayoutParams) actions.getLayoutParams();
        actionParams.height = cardHeight;
        actions.setLayoutParams(actionParams);
    }

    private String buildBankDetail(QuestionRepository.QuestionBank bank) {
        String dateText = bank.isImported() && bank.getImportedAt() > 0
                ? dateFormat.format(new Date(bank.getImportedAt()))
                : "内置题库";
        int imageCount;
        try {
            imageCount = QuestionImageStore.countReferencedImages(
                    QuestionRepository.load(this, bank.getId())
            );
        } catch (Exception ignored) {
            imageCount = 0;
        }
        return "导入日期：" + dateText
                + " · 总题数：" + bank.getQuestionCount()
                + " · 图片：" + imageCount;
    }

    private void bindCardGestures(FrameLayout row, MaterialCardView card, QuestionRepository.QuestionBank bank) {
        int actionWidth = dp(216);
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
                            float nextTranslation = Math.max(-actionWidth, Math.min(0, startTranslationX[0] + dx));
                            view.setTranslationX(nextTranslation);
                            return true;
                        }
                        if (Math.abs(dy) > touchSlop) {
                            cancelLongPress(startDrag, longPressArmed);
                        }
                    }

                    if (swiping[0]) {
                        float nextTranslation = Math.max(-actionWidth, Math.min(0, startTranslationX[0] + dx));
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
                                .translationX(view.getTranslationX() < -actionWidth / 2f ? -actionWidth : 0)
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

    private void editBank(QuestionRepository.QuestionBank bank) {
        if (bank.isImported()) {
            openEditor(bank.getId());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("复制内置题库")
                .setMessage("内置题库不能直接修改。是否复制为自定义副本后编辑？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("复制并编辑", (dialog, which) -> {
                    try {
                        QuestionRepository.QuestionBank copy =
                                QuestionRepository.copyQuestionBankForEditing(this, bank.getId());
                        selectedQuestionBankId = copy.getId();
                        persistSelectedQuestionBankId();
                        openEditor(copy.getId());
                    } catch (Exception e) {
                        Toast.makeText(this, "复制题库失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void openEditor(String questionBankId) {
        Intent intent = new Intent(this, QuestionBankEditorActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUESTION_BANK, questionBankId);
        startActivity(intent);
    }

    private void openDownloadLibraryForImport() {
        Intent intent = new Intent(this, DownloadLibraryActivity.class);
        intent.putExtra(DownloadLibraryActivity.EXTRA_IMPORT_MODE, true);
        libraryImportLauncher.launch(intent);
    }

    private void handleLibraryImportResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            return;
        }
        String importedBankId = result.getData().getStringExtra(
                DownloadLibraryActivity.EXTRA_IMPORTED_BANK_ID
        );
        if (importedBankId == null || importedBankId.trim().isEmpty()) {
            return;
        }
        selectedQuestionBankId = importedBankId;
        persistSelectedQuestionBankId();
        QuestionRepository.resetCache();
        renderCards();
    }

    private void handleMaterialImportResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            return;
        }
        String importedBankId = result.getData().getStringExtra(
                MaterialToQuizActivity.EXTRA_CREATED_BANK_ID
        );
        if (importedBankId == null || importedBankId.trim().isEmpty()) {
            return;
        }
        selectedQuestionBankId = importedBankId;
        persistSelectedQuestionBankId();
        QuestionRepository.resetCache();
        renderCards();
    }

    private void startAutomaticExport(QuestionRepository.QuestionBank bank) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionBank = bank;
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        exportToDownloadLibrary(bank);
    }

    private void handleStoragePermissionResult(boolean granted) {
        QuestionRepository.QuestionBank bank = pendingPermissionBank;
        pendingPermissionBank = null;
        if (bank == null) {
            return;
        }
        if (granted) {
            exportToDownloadLibrary(bank);
        } else {
            Toast.makeText(
                    this,
                    "未获得存储权限，将使用系统“另存为”",
                    Toast.LENGTH_LONG
            ).show();
            exportWithSystemPicker(bank);
        }
    }

    private void exportToDownloadLibrary(QuestionRepository.QuestionBank bank) {
        if (packageOperationRunning) {
            Toast.makeText(this, "题库包正在处理中", Toast.LENGTH_SHORT).show();
            return;
        }
        packageOperationRunning = true;
        Toast.makeText(this, "正在生成题库包…", Toast.LENGTH_SHORT).show();
        packageExecutor.execute(() -> {
            QuestionBankPackageManager.ExportedPackage exported = null;
            try {
                exported = QuestionBankPackageManager.exportToCache(this, bank);
                DownloadArchiveStore.ArchiveRecord saved =
                        DownloadArchiveStore.saveExport(this, bank.getId(), exported);
                runOnUiThread(() -> {
                    packageOperationRunning = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    showExportComplete(saved);
                });
            } catch (Exception e) {
                showPackageError("导出失败", e);
            } finally {
                if (exported != null) {
                    //noinspection ResultOfMethodCallIgnored
                    exported.getFile().delete();
                }
            }
        });
    }

    private void exportWithSystemPicker(QuestionRepository.QuestionBank bank) {
        if (packageOperationRunning) {
            Toast.makeText(this, "题库包正在处理中", Toast.LENGTH_SHORT).show();
            return;
        }
        packageOperationRunning = true;
        Toast.makeText(this, "正在生成题库包…", Toast.LENGTH_SHORT).show();
        packageExecutor.execute(() -> {
            try {
                QuestionBankPackageManager.ExportedPackage exported =
                        QuestionBankPackageManager.exportToCache(this, bank);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        packageOperationRunning = false;
                        //noinspection ResultOfMethodCallIgnored
                        exported.getFile().delete();
                        return;
                    }
                    pendingExport = exported;
                    saveZipLauncher.launch(exported.getDisplayFileName());
                });
            } catch (Exception e) {
                showPackageError("导出失败", e);
            }
        });
    }

    private void showExportComplete(DownloadArchiveStore.ArchiveRecord saved) {
        new AlertDialog.Builder(this)
                .setTitle("已保存到下载库")
                .setMessage(saved.getDisplayName() + "\n\n保存位置：" + DownloadArchiveStore.DISPLAY_FOLDER)
                .setNegativeButton("完成", null)
                .setNeutralButton("分享", (dialog, which) -> shareArchive(saved))
                .setPositiveButton("查看下载库", (dialog, which) ->
                        startActivity(new Intent(this, DownloadLibraryActivity.class)))
                .show();
    }

    private void shareArchive(DownloadArchiveStore.ArchiveRecord record) {
        try {
            Uri uri = DownloadArchiveStore.shareUri(this, record);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/zip");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, record.getDisplayName());
            share.setClipData(ClipData.newUri(getContentResolver(), record.getDisplayName(), uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "分享题库包"));
        } catch (Exception e) {
            showPackageError("无法打开分享页面", e);
        }
    }

    private void handleSaveDestination(Uri destination) {
        QuestionBankPackageManager.ExportedPackage exported = pendingExport;
        pendingExport = null;
        if (destination == null || exported == null) {
            packageOperationRunning = false;
            if (exported != null) {
                //noinspection ResultOfMethodCallIgnored
                exported.getFile().delete();
            }
            return;
        }
        packageExecutor.execute(() -> {
            try (InputStream input = new FileInputStream(exported.getFile());
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IllegalStateException("无法写入所选位置");
                }
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                runOnUiThread(() -> {
                    packageOperationRunning = false;
                    //noinspection ResultOfMethodCallIgnored
                    exported.getFile().delete();
                    Toast.makeText(this, "题库包已保存", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                exported.getFile().delete();
                showPackageError("保存失败", e);
            }
        });
    }

    private void showPackageError(String title, Exception error) {
        runOnUiThread(() -> {
            packageOperationRunning = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(error.getMessage() == null ? "题库包处理失败" : error.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
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
                    new ProgressStore(this).clearQuestionBankData(bank.getId());
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

    @Override
    protected void onDestroy() {
        packageExecutor.shutdownNow();
        super.onDestroy();
    }
}
