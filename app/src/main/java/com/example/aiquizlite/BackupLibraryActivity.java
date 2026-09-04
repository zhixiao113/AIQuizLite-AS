package com.example.aiquizlite;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackupLibraryActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> selectedLocations = new HashSet<>();
    private boolean selectionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        buildLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildLayout();
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.brand_surface));
        LinearLayout content = vertical(0);
        content.setPadding(dp(22), dp(22), dp(22), dp(34));
        scroll.addView(content, matchWrap());

        content.addView(header(), matchWrap());
        content.addView(pathCard(), marginTop(22));
        if (selectionMode) content.addView(batchBar(), marginTop(14));

        List<BackupArchiveStore.ArchiveRecord> records = BackupArchiveStore.list(this);
        TextView count = text("全部备份  ·  " + records.size() + " 个文件", 15,
                R.color.brand_text_secondary, true);
        count.setPadding(dp(4), dp(24), 0, dp(10));
        content.addView(count, matchWrap());

        MaterialCardView listCard = card(Color.WHITE, 20, 0xFFE5E7EB);
        LinearLayout rows = vertical(0);
        listCard.addView(rows, matchWrap());
        if (records.isEmpty()) {
            TextView empty = text("暂无完整备份\n请从“备份与数据”页面创建", 14,
                    R.color.brand_text_secondary, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(44), dp(16), dp(44));
            rows.addView(empty, matchWrap());
        } else {
            for (int index = 0; index < records.size(); index++) {
                if (index > 0) rows.addView(divider(), matchWrap());
                rows.addView(recordRow(records.get(index)), matchWrap());
            }
        }
        content.addView(listCard, matchWrap());
        setContentView(scroll);
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 38, R.color.brand_primary, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rounded(0xFFEEF4FF, 16));
        back.setOnClickListener(view -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout labels = vertical(0);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.leftMargin = dp(16);
        row.addView(labels, labelsParams);
        labels.addView(text("备份库", 28, R.color.brand_ink, true));
        labels.addView(text("完整备份、恢复与分享", 14,
                R.color.brand_text_secondary, false));

        TextView multi = text(selectionMode ? "完成" : "多选", 15,
                R.color.brand_primary, true);
        multi.setPadding(dp(12), dp(12), dp(2), dp(12));
        multi.setOnClickListener(view -> {
            selectionMode = !selectionMode;
            selectedLocations.clear();
            buildLayout();
        });
        row.addView(multi, wrapWrap());
        return row;
    }

    private View pathCard() {
        MaterialCardView card = card(0xFFEEF4FF, 20, 0xFFD8E5FF);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.addView(row, matchWrap());
        TextView icon = text("▰", 24, R.color.brand_primary, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, 15));
        row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout info = vertical(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(14);
        row.addView(info, params);
        info.addView(text(BackupArchiveStore.DISPLAY_FOLDER, 15, R.color.brand_ink, true));
        info.addView(text("应用创建的完整备份 · 越新越靠前", 12,
                R.color.brand_text_secondary, false));
        TextView count = text(BackupArchiveStore.list(this).size() + " 个", 14,
                R.color.brand_primary, true);
        count.setPadding(dp(12), dp(8), dp(12), dp(8));
        count.setBackground(rounded(Color.WHITE, 18));
        row.addView(count, wrapWrap());
        return card;
    }

    private View batchBar() {
        MaterialCardView card = card(0xFF111827, 17, 0xFF111827);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(8), dp(9));
        card.addView(row, matchWrap());
        TextView count = text("已选 " + selectedLocations.size() + " 个", 14,
                android.R.color.white, true);
        row.addView(count, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView all = darkAction("全选");
        all.setOnClickListener(view -> {
            selectedLocations.clear();
            for (BackupArchiveStore.ArchiveRecord record : BackupArchiveStore.list(this)) {
                selectedLocations.add(record.getLocation());
            }
            buildLayout();
        });
        row.addView(all, wrapWrap());
        TextView share = darkAction("分享");
        share.setOnClickListener(view -> shareSelected());
        row.addView(share, wrapWrap());
        TextView delete = darkAction("删除");
        delete.setTextColor(getColor(R.color.brand_error));
        delete.setOnClickListener(view -> confirmDeleteSelected());
        row.addView(delete, wrapWrap());
        return card;
    }

    private View recordRow(BackupArchiveStore.ArchiveRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(13), dp(10), dp(13));
        if (selectionMode) {
            CheckBox check = new CheckBox(this);
            check.setChecked(selectedLocations.contains(record.getLocation()));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedLocations.add(record.getLocation());
                else selectedLocations.remove(record.getLocation());
                buildLayout();
            });
            row.addView(check, new LinearLayout.LayoutParams(dp(44), dp(48)));
        }

        TextView icon = text("ZIP", 12, R.color.brand_primary, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0xFFEEF4FF, 14));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout info = vertical(0);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.leftMargin = dp(12);
        row.addView(info, infoParams);
        TextView name = text(record.getDisplayName(), 14, R.color.brand_ink, true);
        name.setSingleLine(true);
        info.addView(name);
        info.addView(text(formatTime(record.getCreatedAt()) + " · " + formatSize(record.getSize()),
                12, R.color.brand_text_secondary, false));

        if (selectionMode) {
            row.setOnClickListener(view -> {
                if (!selectedLocations.add(record.getLocation())) {
                    selectedLocations.remove(record.getLocation());
                }
                buildLayout();
            });
        } else {
            TextView restore = compactAction("恢复", R.color.brand_primary);
            restore.setOnClickListener(view -> openRestore(record));
            row.addView(restore, wrapWrap());
            TextView more = compactAction("⋮", R.color.brand_text_secondary);
            more.setOnClickListener(view -> showActions(record));
            row.addView(more, wrapWrap());
        }
        return row;
    }

    private void openRestore(BackupArchiveStore.ArchiveRecord record) {
        Intent intent = new Intent(this, BackupDataActivity.class);
        intent.putExtra(BackupDataActivity.EXTRA_ARCHIVE_LOCATION, record.getLocation());
        startActivity(intent);
    }

    private void showActions(BackupArchiveStore.ArchiveRecord record) {
        new AlertDialog.Builder(this)
                .setTitle(record.getDisplayName())
                .setItems(new String[]{"分享", "删除"}, (dialog, which) -> {
                    if (which == 0) shareRecords(List.of(record));
                    else confirmDelete(List.of(record));
                })
                .show();
    }

    private void shareSelected() {
        List<BackupArchiveStore.ArchiveRecord> records = selectedRecords();
        if (records.isEmpty()) {
            toast("请先选择备份");
            return;
        }
        shareRecords(records);
    }

    private void shareRecords(List<BackupArchiveStore.ArchiveRecord> records) {
        if (records.size() == 1) {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, BackupArchiveStore.shareUri(this, records.get(0)));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享完整备份"));
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (BackupArchiveStore.ArchiveRecord record : records) {
            uris.add(BackupArchiveStore.shareUri(this, record));
        }
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("application/zip");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享完整备份"));
    }

    private void confirmDeleteSelected() {
        List<BackupArchiveStore.ArchiveRecord> records = selectedRecords();
        if (records.isEmpty()) {
            toast("请先选择备份");
            return;
        }
        confirmDelete(records);
    }

    private void confirmDelete(List<BackupArchiveStore.ArchiveRecord> records) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + records.size() + " 个备份？")
                .setMessage("删除后无法从备份库恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                    try {
                        for (BackupArchiveStore.ArchiveRecord record : records) {
                            BackupArchiveStore.delete(this, record);
                        }
                        runOnUiThread(() -> {
                            selectedLocations.clear();
                            buildLayout();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> toast(error.getMessage() == null
                                ? "删除失败" : error.getMessage()));
                    }
                }))
                .show();
    }

    private List<BackupArchiveStore.ArchiveRecord> selectedRecords() {
        List<BackupArchiveStore.ArchiveRecord> selected = new ArrayList<>();
        for (BackupArchiveStore.ArchiveRecord record : BackupArchiveStore.list(this)) {
            if (selectedLocations.contains(record.getLocation())) selected.add(record);
        }
        return selected;
    }

    private TextView darkAction(String value) {
        TextView view = text(value, 13, android.R.color.white, true);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        return view;
    }

    private TextView compactAction(String value, int colorRes) {
        TextView view = text(value, 13, colorRes, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(9), dp(10), dp(9), dp(10));
        return view;
    }

    private MaterialCardView card(int color, int radius, int stroke) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color);
        card.setRadius(dp(radius));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(stroke);
        return card;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(0xFFE5E7EB);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(74);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout vertical(int padding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        return layout;
    }

    private TextView text(String value, int sp, int colorRes, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getColor(colorRes));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private String formatTime(long value) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(value));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
