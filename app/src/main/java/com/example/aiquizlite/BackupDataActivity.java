package com.example.aiquizlite;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackupDataActivity extends AppCompatActivity {
    public static final String EXTRA_ARCHIVE_LOCATION = "backup_archive_location";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String[]> openBackupLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private boolean handledRequestedArchive;
    private boolean creatingBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(getColor(R.color.brand_surface));
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView()
        );
        controller.setAppearanceLightStatusBars(true);
        openBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) prepareFromUri(uri);
                }
        );
        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) createBackupNow();
                    else toast("需要存储权限才能在旧版 Android 的下载目录创建备份");
                }
        );
        buildLayout();
        openRequestedArchive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (openBackupLauncher != null) buildLayout();
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.brand_surface));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(36));
        scroll.addView(content, matchWrap());

        content.addView(header(), matchWrap());
        content.addView(space(22), matchWrap());
        content.addView(protectionCard(), matchWrap());
        content.addView(sectionTitle("最近备份"), matchWrap());
        addRecentBackups(content);
        content.addView(sectionTitle("数据管理"), matchWrap());
        content.addView(dataManagementCard(), matchWrap());
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

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(16);
        row.addView(labels, params);
        labels.addView(text("备份与数据", 28, R.color.brand_ink, true));
        TextView subtitle = text("保护本地题库与学习进度", 14,
                R.color.brand_text_secondary, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        labels.addView(subtitle);
        return row;
    }

    private View protectionCard() {
        MaterialCardView card = card(0xFFEEF4FF, 24, 0xFFD8E5FF);
        LinearLayout body = vertical(dp(20));
        card.addView(body, matchWrap());

        TextView badge = text("✓  本地数据可备份", 13, R.color.brand_primary, true);
        badge.setPadding(dp(12), dp(7), dp(12), dp(7));
        badge.setBackground(rounded(Color.WHITE, 18));
        body.addView(badge, wrapWrap());

        List<BackupArchiveStore.ArchiveRecord> records = BackupArchiveStore.list(this);
        TextView title = text(records.isEmpty() ? "还没有完整备份" : "最近一次已保护",
                23, R.color.brand_ink, true);
        title.setPadding(0, dp(16), 0, dp(4));
        body.addView(title);
        String detail = records.isEmpty()
                ? "创建后保存到 " + BackupArchiveStore.DISPLAY_FOLDER
                : formatTime(records.get(0).getCreatedAt()) + " · "
                + formatSize(records.get(0).getSize());
        body.addView(text(detail, 13, R.color.brand_text_secondary, false));

        FullBackupManager.BackupSummary summary =
                FullBackupManager.currentSummary(this, System.currentTimeMillis());
        TextView scope = text(
                "将保护 " + summary.getImportedBankCount() + " 个导入题库、"
                        + summary.getQuestionCount() + " 道题、"
                        + summary.getImageCount() + " 张图片，以及学习记录和普通设置。\n"
                        + "API Key、AI 配置、下载库题库 ZIP 不会进入备份。",
                13, R.color.brand_text_secondary, false
        );
        scope.setLineSpacing(dp(3), 1f);
        scope.setPadding(0, dp(14), 0, dp(16));
        body.addView(scope);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button create = actionButton("创建完整备份", true);
        create.setOnClickListener(view -> createBackup());
        actions.addView(create, weightedButton(1f));
        Button restore = actionButton("从文件恢复", false);
        restore.setOnClickListener(view -> openBackupLauncher.launch(
                new String[]{"application/zip", "application/octet-stream"}
        ));
        LinearLayout.LayoutParams restoreParams = weightedButton(1f);
        restoreParams.leftMargin = dp(10);
        actions.addView(restore, restoreParams);
        body.addView(actions, matchWrap());
        return card;
    }

    private void addRecentBackups(LinearLayout content) {
        List<BackupArchiveStore.ArchiveRecord> records = BackupArchiveStore.list(this);
        MaterialCardView card = card(Color.WHITE, 20, 0xFFE5E7EB);
        LinearLayout rows = vertical(0);
        card.addView(rows, matchWrap());
        if (records.isEmpty()) {
            TextView empty = text("创建完整备份后，会在这里显示最近记录。", 14,
                    R.color.brand_text_secondary, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(28), dp(18), dp(28));
            rows.addView(empty, matchWrap());
        } else {
            int count = Math.min(2, records.size());
            for (int index = 0; index < count; index++) {
                if (index > 0) rows.addView(divider(), matchWrap());
                rows.addView(backupRow(records.get(index)), matchWrap());
            }
        }
        content.addView(card, matchWrap());

        TextView all = text("查看全部备份  ›", 14, R.color.brand_primary, true);
        all.setGravity(Gravity.CENTER);
        all.setPadding(0, dp(14), 0, dp(2));
        all.setOnClickListener(view -> startActivity(new Intent(this, BackupLibraryActivity.class)));
        content.addView(all, matchWrap());
    }

    private View backupRow(BackupArchiveStore.ArchiveRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(10), dp(13));
        TextView icon = text("ZIP", 12, R.color.brand_primary, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0xFFEEF4FF, 14));
        row.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout info = vertical(0);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.leftMargin = dp(12);
        row.addView(info, infoParams);
        TextView name = text(record.getDisplayName(), 14, R.color.brand_ink, true);
        name.setSingleLine(true);
        info.addView(name);
        info.addView(text(formatTime(record.getCreatedAt()) + " · " + formatSize(record.getSize()),
                12, R.color.brand_text_secondary, false));

        TextView restore = compactAction("恢复", R.color.brand_primary);
        restore.setOnClickListener(view -> prepareRecord(record));
        row.addView(restore, wrapWrap());
        TextView more = compactAction("⋮", R.color.brand_text_secondary);
        more.setOnClickListener(view -> showRecordActions(record));
        row.addView(more, wrapWrap());
        return row;
    }

    private View dataManagementCard() {
        MaterialCardView card = card(Color.WHITE, 20, 0xFFE5E7EB);
        LinearLayout rows = vertical(0);
        card.addView(rows, matchWrap());
        rows.addView(managementRow("清理学习记录", "错题、收藏、日常练习和模考记录",
                view -> confirmClear("清理学习记录", "此操作不会删除题库。", () ->
                        FullBackupManager.clearLearningRecords(this))), matchWrap());
        rows.addView(divider(), matchWrap());
        rows.addView(managementRow("删除导入题库", "删除自建/导入题库及其图片",
                view -> confirmClear("删除全部导入题库", "内置题库会保留。", () ->
                        QuestionRepository.clearImportedQuestionBanks(this))), matchWrap());
        rows.addView(divider(), matchWrap());
        rows.addView(managementRow("清理 AI 讲解缓存", "不影响 API Key 与 AI 配置",
                view -> confirmClear("清理 AI 讲解缓存", "之后可按需重新生成讲解。", () ->
                        new AiExplanationCache(this).clear())), matchWrap());
        rows.addView(divider(), matchWrap());
        rows.addView(managementRow("恢复普通设置", "将练习、考试、显示和下载设置恢复默认",
                view -> confirmClear("恢复普通设置", "API Key 与 AI 配置不会改变。", () ->
                        FullBackupManager.clearOrdinarySettings(this))), matchWrap());
        rows.addView(divider(), matchWrap());
        TextView danger = text("清除全部本地学习数据  ›", 15, R.color.brand_error, true);
        danger.setPadding(dp(18), dp(18), dp(18), dp(18));
        danger.setOnClickListener(view -> confirmDangerousReset());
        rows.addView(danger, matchWrap());
        return card;
    }

    private View managementRow(String title, String detail, View.OnClickListener listener) {
        LinearLayout row = vertical(dp(16));
        row.addView(text(title + "  ›", 15, R.color.brand_ink, true));
        TextView description = text(detail, 12, R.color.brand_text_secondary, false);
        description.setPadding(0, dp(4), 0, 0);
        row.addView(description);
        row.setOnClickListener(listener);
        return row;
    }

    private void createBackup() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        createBackupNow();
    }

    private void createBackupNow() {
        if (creatingBackup) {
            toast("备份正在创建，请稍候");
            return;
        }
        creatingBackup = true;
        toast("正在创建完整备份…");
        executor.execute(() -> {
            try {
                FullBackupManager.CreatedBackup created = FullBackupManager.create(this);
                BackupArchiveStore.ArchiveRecord record = BackupArchiveStore.save(this, created);
                //noinspection ResultOfMethodCallIgnored
                created.getFile().delete();
                runOnUiThread(() -> {
                    creatingBackup = false;
                    toast("已保存到 " + BackupArchiveStore.DISPLAY_FOLDER);
                    buildLayout();
                });
            } catch (Exception error) {
                runOnUiThread(() -> creatingBackup = false);
                showError("创建备份失败", error);
            }
        });
    }

    private void prepareFromUri(Uri uri) {
        toast("正在检查备份…");
        executor.execute(() -> {
            try {
                FullBackupManager.PreparedBackup prepared = FullBackupManager.prepare(
                        this, getContentResolver().openInputStream(uri)
                );
                runOnUiThread(() -> showRestorePreview(prepared));
            } catch (Exception error) {
                showError("无法读取这个备份", error);
            }
        });
    }

    private void prepareRecord(BackupArchiveStore.ArchiveRecord record) {
        toast("正在检查备份…");
        executor.execute(() -> {
            try {
                FullBackupManager.PreparedBackup prepared = FullBackupManager.prepare(
                        this, BackupArchiveStore.open(this, record)
                );
                runOnUiThread(() -> showRestorePreview(prepared));
            } catch (Exception error) {
                showError("无法读取这个备份", error);
            }
        });
    }

    private void openRequestedArchive() {
        if (handledRequestedArchive) return;
        String location = getIntent().getStringExtra(EXTRA_ARCHIVE_LOCATION);
        if (location == null || location.isEmpty()) return;
        handledRequestedArchive = true;
        for (BackupArchiveStore.ArchiveRecord record : BackupArchiveStore.list(this)) {
            if (location.equals(record.getLocation())) {
                prepareRecord(record);
                return;
            }
        }
        toast("备份文件已不存在");
    }

    private void showRestorePreview(FullBackupManager.PreparedBackup prepared) {
        FullBackupManager.BackupSummary summary = prepared.getSummary();
        String message = "备份时间：" + formatTime(summary.getCreatedAt())
                + "\n\n将恢复：\n"
                + "• " + summary.getImportedBankCount() + " 个导入题库，"
                + summary.getQuestionCount() + " 道题\n"
                + "• " + summary.getImageCount() + " 张题目图片\n"
                + "• 学习记录、模考记录和普通设置\n\n"
                + "现有对应数据将被完全替换。API Key、AI 配置和下载库 ZIP 保持不变；"
                + "恢复失败时会自动回滚。";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("确认恢复完整备份")
                .setMessage(message)
                .setNegativeButton("取消", (ignored, which) -> prepared.discard())
                .setPositiveButton("完全恢复", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    dialog.dismiss();
                    performRestore(prepared);
                }));
        dialog.setOnCancelListener(ignored -> prepared.discard());
        dialog.show();
    }

    private void performRestore(FullBackupManager.PreparedBackup prepared) {
        toast("正在恢复，请勿关闭应用…");
        executor.execute(() -> {
            try {
                FullBackupManager.restore(this, prepared);
                prepared.discard();
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("恢复完成")
                        .setMessage("题库、学习记录和普通设置已恢复。API Key 与 AI 配置未改变。")
                        .setPositiveButton("知道了", (dialog, which) -> buildLayout())
                        .show());
            } catch (Exception error) {
                prepared.discard();
                showError("恢复失败，原数据已尝试回滚", error);
            }
        });
    }

    private void showRecordActions(BackupArchiveStore.ArchiveRecord record) {
        new AlertDialog.Builder(this)
                .setTitle(record.getDisplayName())
                .setItems(new String[]{"分享", "删除"}, (dialog, which) -> {
                    if (which == 0) share(record);
                    else confirmDelete(record);
                })
                .show();
    }

    private void share(BackupArchiveStore.ArchiveRecord record) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, BackupArchiveStore.shareUri(this, record));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享完整备份"));
    }

    private void confirmDelete(BackupArchiveStore.ArchiveRecord record) {
        new AlertDialog.Builder(this)
                .setTitle("删除这个备份？")
                .setMessage(record.getDisplayName())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                    try {
                        BackupArchiveStore.delete(this, record);
                        runOnUiThread(this::buildLayout);
                    } catch (Exception error) {
                        showError("删除失败", error);
                    }
                }))
                .show();
    }

    private void confirmClear(String title, String detail, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title + "？")
                .setMessage(detail + " 建议先创建完整备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> {
                    action.run();
                    toast("已完成");
                    buildLayout();
                })
                .show();
    }

    private void confirmDangerousReset() {
        new AlertDialog.Builder(this)
                .setTitle("清除全部本地学习数据？")
                .setMessage("将清除导入题库、图片、学习与模考记录、AI 讲解缓存和普通设置。"
                        + "API Key、AI 配置与下载目录中的 ZIP 会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("最后确认")
                        .setMessage("此操作无法撤销。请确认已经创建需要的完整备份。")
                        .setNegativeButton("返回", null)
                        .setPositiveButton("确认清除", (finalDialog, finalWhich) -> {
                            FullBackupManager.clearAllNonSensitiveData(this);
                            toast("本地学习数据已清除");
                            buildLayout();
                        })
                        .show())
                .show();
    }

    private MaterialCardView card(int color, int radiusDp, int strokeColor) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color);
        card.setRadius(dp(radiusDp));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(strokeColor);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 17, R.color.brand_ink, true);
        title.setPadding(dp(4), dp(28), 0, dp(10));
        return title;
    }

    private Button actionButton(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : getColor(R.color.brand_primary));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(primary
                ? rounded(getColor(R.color.brand_primary), 15)
                : outlinedRounded(Color.WHITE, getColor(R.color.brand_primary), 15));
        return button;
    }

    private TextView compactAction(String value, int colorRes) {
        TextView action = text(value, 13, colorRes, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(10), dp(10), dp(10));
        return action;
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

    private View space(int height) {
        View view = new View(this);
        view.setMinimumHeight(dp(height));
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

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlinedRounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams weightedButton(float weight) {
        return new LinearLayout.LayoutParams(0, dp(50), weight);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String formatTime(long value) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(value));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
    }

    private void showError(String title, Exception error) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(error.getMessage() == null ? "请稍后重试" : error.getMessage())
                .setPositiveButton("知道了", null)
                .show());
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
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
