package com.example.aiquizlite;

import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadLibraryActivity extends AppCompatActivity {
    public static final String EXTRA_IMPORT_MODE = "download_library_import_mode";
    public static final String EXTRA_IMPORTED_BANK_ID = "download_library_imported_bank_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<DownloadArchiveStore.ArchiveRecord> records = new ArrayList<>();
    private final Set<String> selectedLocations = new HashSet<>();
    private final SimpleDateFormat todayFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat olderFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private boolean importMode;
    private boolean selecting;
    private boolean operationRunning;
    private LinearLayout contentContainer;
    private LinearLayout batchBar;
    private TextView manageButton;
    private TextView selectedCountText;
    private TextView operationBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        importMode = getIntent().getBooleanExtra(EXTRA_IMPORT_MODE, false);
        buildLayout();
        renderRecords();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (contentContainer != null && !operationRunning) {
            renderRecords();
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.brand_surface));
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        MaterialButton backButton = new MaterialButton(this);
        backButton.setText("‹");
        backButton.setTextSize(26);
        backButton.setTextColor(getColor(R.color.brand_primary));
        backButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_primary_soft)));
        backButton.setMinWidth(dp(44));
        backButton.setMinHeight(dp(44));
        backButton.setInsetTop(0);
        backButton.setInsetBottom(0);
        backButton.setPadding(0, 0, 0, dp(2));
        backButton.setOnClickListener(view -> finish());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        titleParams.leftMargin = dp(12);
        header.addView(titles, titleParams);

        TextView title = text(importMode ? "选择题库包" : "下载库", 24, R.color.brand_ink, true);
        titles.addView(title);
        TextView subtitle = text(
                importMode ? "从下载库导入 ZIP" : "题库备份与分享",
                12,
                R.color.brand_text_secondary,
                false
        );
        titles.addView(subtitle);

        manageButton = text("多选", 15, R.color.brand_primary, true);
        manageButton.setGravity(Gravity.CENTER);
        manageButton.setPadding(dp(10), dp(10), dp(4), dp(10));
        manageButton.setVisibility(importMode ? View.GONE : View.VISIBLE);
        manageButton.setOnClickListener(view -> toggleSelectionMode());
        header.addView(manageButton);

        operationBanner = text("", 13, R.color.brand_primary_dark, true);
        operationBanner.setGravity(Gravity.CENTER_VERTICAL);
        operationBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        operationBanner.setBackground(roundedBackground(
                getColor(R.color.brand_primary_soft),
                getColor(R.color.brand_primary_border),
                14
        ));
        operationBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams bannerParams = matchWrap();
        bannerParams.topMargin = dp(14);
        root.addView(operationBanner, bannerParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        scrollParams.topMargin = dp(16);
        root.addView(scrollView, scrollParams);

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentContainer, matchWrap());

        batchBar = new LinearLayout(this);
        batchBar.setGravity(Gravity.CENTER_VERTICAL);
        batchBar.setPadding(dp(12), dp(8), dp(8), dp(8));
        batchBar.setBackground(roundedBackground(0xFF111827, 0xFF111827, 16));
        batchBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams batchParams = matchWrap();
        batchParams.topMargin = dp(10);
        root.addView(batchBar, batchParams);

        selectedCountText = text("已选 0 项", 13, android.R.color.white, true);
        batchBar.addView(selectedCountText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        batchBar.addView(batchButton("全选", view -> selectAll()));
        batchBar.addView(batchButton("分享", view -> shareSelected()));
        MaterialButton deleteButton = batchButton("删除", view -> confirmDelete(selectedRecords()));
        deleteButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_error_dark)));
        batchBar.addView(deleteButton);
    }

    private void renderRecords() {
        records.clear();
        records.addAll(DownloadArchiveStore.list(this));
        selectedLocations.retainAll(recordLocations(records));
        contentContainer.removeAllViews();

        if (importMode) {
            TextView hint = text(
                    "选择一个 ZIP 立即导入。文件按时间倒序排列，最新文件在最前面。",
                    13,
                    R.color.brand_text_secondary,
                    false
            );
            hint.setPadding(dp(14), dp(12), dp(14), dp(12));
            hint.setBackground(roundedBackground(0xFFFFF8E5, 0xFFF2D68A, 14));
            contentContainer.addView(hint, matchWrap());
        }

        contentContainer.addView(createPathCard(), topMargin(14));

        if (records.isEmpty()) {
            TextView empty = text(
                    importMode
                            ? "下载库中还没有可导入的 ZIP。\n请先从题库管理导出题库包。"
                            : "下载库还是空的。\n从题库管理左滑题库并点击“导出”后，ZIP 会自动出现在这里。",
                    14,
                    R.color.brand_text_secondary,
                    false
            );
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(70), dp(20), dp(70));
            contentContainer.addView(empty, matchWrap());
            updateBatchBar();
            return;
        }

        List<DownloadArchiveStore.ArchiveRecord> today = new ArrayList<>();
        List<DownloadArchiveStore.ArchiveRecord> older = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (DownloadArchiveStore.ArchiveRecord record : records) {
            if (DownloadArchiveRules.isToday(record.getCreatedAt(), now, TimeZone.getDefault())) {
                today.add(record);
            } else {
                older.add(record);
            }
        }
        addSection("今天", today);
        addSection("更早", older);
        updateBatchBar();
    }

    private View createPathCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setCardBackgroundColor(getColor(R.color.brand_primary_soft));
        card.setStrokeColor(getColor(R.color.brand_primary_border));
        card.setStrokeWidth(dp(1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.addView(row);

        TextView icon = text("▰", 22, android.R.color.white, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundedBackground(
                getColor(R.color.brand_primary),
                getColor(R.color.brand_primary),
                12
        ));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        labelParams.leftMargin = dp(12);
        row.addView(labels, labelParams);
        labels.addView(text(DownloadArchiveStore.displayFolder(this), 14, R.color.brand_ink, true));
        labels.addView(text("按时间倒序 · 越新越靠前", 12, R.color.brand_text_secondary, false));

        TextView count = text(records.size() + " 个", 12, R.color.brand_primary, true);
        count.setPadding(dp(10), dp(6), dp(10), dp(6));
        count.setBackground(roundedBackground(
                getColor(R.color.brand_card),
                getColor(R.color.brand_card),
                20
        ));
        row.addView(count);
        return card;
    }

    private void addSection(String title, List<DownloadArchiveStore.ArchiveRecord> sectionRecords) {
        if (sectionRecords.isEmpty()) {
            return;
        }
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingParams = topMargin(20);
        contentContainer.addView(heading, headingParams);
        heading.addView(text(title, 16, R.color.brand_ink, true), new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        heading.addView(text(
                sectionRecords.size() + " 个文件",
                12,
                R.color.brand_text_secondary,
                false
        ));

        MaterialCardView listCard = new MaterialCardView(this);
        listCard.setRadius(dp(16));
        listCard.setCardElevation(0);
        listCard.setCardBackgroundColor(getColor(R.color.brand_card));
        listCard.setStrokeColor(getColor(R.color.brand_border));
        listCard.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = topMargin(10);
        contentContainer.addView(listCard, cardParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(list);
        for (int i = 0; i < sectionRecords.size(); i++) {
            list.addView(createRecordRow(sectionRecords.get(i)));
            if (i < sectionRecords.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.brand_divider));
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                );
                dividerParams.leftMargin = dp(68);
                list.addView(divider, dividerParams);
            }
        }
    }

    private View createRecordRow(DownloadArchiveStore.ArchiveRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(14), dp(10), dp(14));
        row.setOnClickListener(view -> {
            if (operationRunning) {
                return;
            }
            if (importMode) {
                importRecord(record);
            } else if (selecting) {
                toggleRecordSelection(record);
            }
        });
        row.setOnLongClickListener(view -> {
            if (importMode || operationRunning) {
                return false;
            }
            selecting = true;
            selectedLocations.add(record.getLocation());
            renderRecords();
            return true;
        });

        CheckBox checkBox = new CheckBox(this);
        checkBox.setButtonTintList(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        checkBox.setChecked(selectedLocations.contains(record.getLocation()));
        checkBox.setVisibility(selecting && !importMode ? View.VISIBLE : View.GONE);
        checkBox.setOnClickListener(view -> toggleRecordSelection(record));
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(36), dp(42)));

        TextView icon = text("ZIP", 12, R.color.brand_primary, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundedBackground(
                getColor(R.color.brand_primary_soft),
                getColor(R.color.brand_primary_soft),
                11
        ));
        row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(50)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        infoParams.leftMargin = dp(12);
        row.addView(info, infoParams);
        TextView name = text(record.getDisplayName(), 14, R.color.brand_ink, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        info.addView(text(
                formatDate(record.getCreatedAt()) + " · " + formatSize(record.getSize()),
                11,
                R.color.brand_text_secondary,
                false
        ));

        if ((!selecting || importMode) && importMode) {
            MaterialButton importAction = smallAction("导入");
            importAction.setOnClickListener(view -> importRecord(record));
            row.addView(importAction);
        } else if (!selecting) {
            ImageButton shareAction = smallIconAction(
                    R.drawable.ic_share_compact,
                    R.color.brand_primary_soft,
                    R.color.brand_primary_border,
                    "分享 " + record.getDisplayName()
            );
            shareAction.setOnClickListener(view -> shareRecords(
                    java.util.Collections.singletonList(record)
            ));
            row.addView(shareAction, iconActionParams());

            ImageButton deleteAction = smallIconAction(
                    R.drawable.ic_delete_compact,
                    R.color.brand_error_soft,
                    R.color.brand_error_soft,
                    "删除 " + record.getDisplayName()
            );
            deleteAction.setOnClickListener(view -> confirmDelete(
                    java.util.Collections.singletonList(record)
            ));
            LinearLayout.LayoutParams deleteParams = iconActionParams();
            deleteParams.leftMargin = dp(7);
            row.addView(deleteAction, deleteParams);
        }
        return row;
    }

    private void toggleSelectionMode() {
        selecting = !selecting;
        if (!selecting) {
            selectedLocations.clear();
        }
        renderRecords();
    }

    private void toggleRecordSelection(DownloadArchiveStore.ArchiveRecord record) {
        if (selectedLocations.contains(record.getLocation())) {
            selectedLocations.remove(record.getLocation());
        } else {
            selectedLocations.add(record.getLocation());
        }
        renderRecords();
    }

    private void selectAll() {
        if (selectedLocations.size() == records.size()) {
            selectedLocations.clear();
        } else {
            selectedLocations.clear();
            selectedLocations.addAll(recordLocations(records));
        }
        renderRecords();
    }

    private void updateBatchBar() {
        manageButton.setText(selecting ? "完成" : "多选");
        batchBar.setVisibility(selecting && !importMode ? View.VISIBLE : View.GONE);
        selectedCountText.setText(String.format(
                Locale.getDefault(),
                "已选 %d 项",
                selectedLocations.size()
        ));
    }

    private void shareSelected() {
        List<DownloadArchiveStore.ArchiveRecord> selected = selectedRecords();
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        shareRecords(selected);
    }

    private void shareRecords(List<DownloadArchiveStore.ArchiveRecord> shareRecords) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (DownloadArchiveStore.ArchiveRecord record : shareRecords) {
                uris.add(DownloadArchiveStore.shareUri(this, record));
            }
            Intent share = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            share.setType("application/zip");
            if (uris.size() == 1) {
                share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            ClipData clipData = ClipData.newUri(
                    getContentResolver(),
                    shareRecords.get(0).getDisplayName(),
                    uris.get(0)
            );
            for (int i = 1; i < uris.size(); i++) {
                clipData.addItem(new ClipData.Item(uris.get(i)));
            }
            share.setClipData(clipData);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "分享题库包"));
        } catch (Exception e) {
            showError("无法分享文件", e);
        }
    }

    private void confirmDelete(List<DownloadArchiveStore.ArchiveRecord> deleteRecords) {
        if (deleteRecords.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(deleteRecords.size() > 1 ? "批量删除文件" : "删除文件")
                .setMessage(deleteRecords.size() > 1
                        ? "确定删除选中的 " + deleteRecords.size() + " 个 ZIP 吗？删除后无法恢复。"
                        : "确定删除这个 ZIP 吗？删除后无法恢复。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (dialog, which) -> deleteRecords(deleteRecords))
                .show();
    }

    private void deleteRecords(List<DownloadArchiveStore.ArchiveRecord> deleteRecords) {
        setOperation("正在删除 " + deleteRecords.size() + " 个文件…", true);
        executor.execute(() -> {
            try {
                for (DownloadArchiveStore.ArchiveRecord record : deleteRecords) {
                    DownloadArchiveStore.delete(this, record);
                }
                runOnUiThread(() -> {
                    selecting = false;
                    selectedLocations.clear();
                    setOperation(null, false);
                    renderRecords();
                    Toast.makeText(this, "文件已删除", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                showError("删除失败", e);
            }
        });
    }

    private void importRecord(DownloadArchiveStore.ArchiveRecord record) {
        if (operationRunning) {
            return;
        }
        setOperation("正在导入：" + record.getDisplayName(), true);
        executor.execute(() -> {
            try (InputStream input = DownloadArchiveStore.open(this, record)) {
                QuestionRepository.QuestionBank imported =
                        QuestionBankPackageManager.importPackage(this, input);
                runOnUiThread(() -> {
                    setOperation(null, false);
                    Intent result = new Intent();
                    result.putExtra(EXTRA_IMPORTED_BANK_ID, imported.getId());
                    setResult(RESULT_OK, result);
                    Toast.makeText(
                            this,
                            "已导入：" + imported.getDisplayName(),
                            Toast.LENGTH_SHORT
                    ).show();
                    finish();
                });
            } catch (Exception e) {
                showError("导入失败", e);
            }
        });
    }

    private void setOperation(String message, boolean running) {
        operationRunning = running;
        operationBanner.setText(message == null ? "" : message);
        operationBanner.setVisibility(running ? View.VISIBLE : View.GONE);
        manageButton.setEnabled(!running);
    }

    private void showError(String title, Exception error) {
        runOnUiThread(() -> {
            setOperation(null, false);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(error.getMessage() == null ? "操作失败" : error.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private List<DownloadArchiveStore.ArchiveRecord> selectedRecords() {
        List<DownloadArchiveStore.ArchiveRecord> selected = new ArrayList<>();
        for (DownloadArchiveStore.ArchiveRecord record : records) {
            if (selectedLocations.contains(record.getLocation())) {
                selected.add(record);
            }
        }
        return selected;
    }

    private Set<String> recordLocations(List<DownloadArchiveStore.ArchiveRecord> source) {
        Set<String> locations = new HashSet<>();
        for (DownloadArchiveStore.ArchiveRecord record : source) {
            locations.add(record.getLocation());
        }
        return locations;
    }

    private String formatDate(long timestamp) {
        return DownloadArchiveRules.isToday(
                timestamp,
                System.currentTimeMillis(),
                TimeZone.getDefault()
        ) ? "今天 " + todayFormat.format(new Date(timestamp))
                : olderFormat.format(new Date(timestamp));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    private TextView text(String value, int size, int colorResource, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(getColor(colorResource));
        if (bold) {
            textView.setTypeface(textView.getTypeface(), Typeface.BOLD);
        }
        return textView;
    }

    private MaterialButton smallAction(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(getColor(R.color.brand_primary));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_primary_soft)));
        button.setStrokeColor(ColorStateList.valueOf(getColor(R.color.brand_primary_border)));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(12));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinHeight(dp(38));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private ImageButton smallIconAction(
            int iconResource,
            int fillColorResource,
            int strokeColorResource,
            String description
    ) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setContentDescription(description);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setBackground(roundedBackground(
                getColor(fillColorResource),
                getColor(strokeColorResource),
                12
        ));
        button.setElevation(0f);
        return button;
    }

    private LinearLayout.LayoutParams iconActionParams() {
        return new LinearLayout.LayoutParams(dp(40), dp(40));
    }

    private MaterialButton batchButton(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(android.graphics.Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(0xFF29344A));
        button.setMinWidth(0);
        button.setMinHeight(dp(38));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        params.leftMargin = dp(5);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable roundedBackground(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
