package com.example.aiquizlite;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线更新对话框：
 * 1) 发现新版本确认框（渐变横幅卡片）
 * 2) 大圆环下载进度框（可取消，取消即中断下载并清理文件）
 * 两个界面都贴合 App 蓝色学习主题。
 */
public final class UpdateDialog {

    private UpdateDialog() {
    }

    /**
     * 完整更新流程入口：检测到新版本后调用。
     * 显示确认框 → 用户点"立即更新" → 圆环下载 → 校验通过后交给系统安装。
     */
    public static void startUpdateFlow(
            Activity activity,
            String serverUrl,
            UpdateChecker.UpdateInfo info
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || info == null) {
            return;
        }
        showUpdateAvailable(activity, serverUrl, info);
    }

    private static void showUpdateAvailable(
            Activity activity,
            String serverUrl,
            UpdateChecker.UpdateInfo info
    ) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update_available);
        styleDialog(dialog, 0.85f);

        TextView title = dialog.findViewById(R.id.newVersionTitle);
        TextView sub = dialog.findViewById(R.id.versionSubtitle);
        LinearLayout log = dialog.findViewById(R.id.changelogContainer);

        title.setText("v" + info.versionName + " 新版本");
        sub.setText("当前 v" + BuildConfig.VERSION_NAME + " · 更新到 v" + info.versionName);

        String notes = info.notes == null ? "" : info.notes.trim();
        String[] lines = notes.isEmpty()
                ? new String[]{"新版本发布，建议更新。"}
                : notes.split("[\\n;；]+");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                View row = buildChangelogRow(activity, line);
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) row.getLayoutParams();
                lp.bottomMargin = (i == lines.length - 1) ? 0 : dp(activity, 10);
                row.setLayoutParams(lp);
                log.addView(row);
            }
        }

        dialog.findViewById(R.id.laterButton).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnCancelListener(d -> {
            // 返回键或点击外部 = 稍后再说
        });
        dialog.findViewById(R.id.updateButton).setOnClickListener(v -> {
            dialog.dismiss();
            startDownload(activity, serverUrl, info);
        });
        dialog.show();
    }

    private static View buildChangelogRow(Context context, String text) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tick = new TextView(context);
        tick.setBackgroundResource(R.drawable.bg_tick_circle);
        tick.setText("✓");
        tick.setTextColor(Color.WHITE);
        tick.setTextSize(10);
        tick.setGravity(Gravity.CENTER);
        tick.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tickLp =
                new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
        tickLp.topMargin = dp(context, 1);
        row.addView(tick, tickLp);

        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextColor(0xFF33415C);
        textView.setTextSize(12.5f);
        textView.setLineSpacing(0f, 1.1f);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(context, 10));
        row.addView(textView, textLp);

        return row;
    }

    private static void startDownload(
            Activity activity,
            String serverUrl,
            UpdateChecker.UpdateInfo info
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        AtomicBoolean cancelFlag = new AtomicBoolean(false);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_download_progress);
        styleDialog(dialog, 0.72f);

        RingProgressView ring = dialog.findViewById(R.id.ringProgress);
        TextView subtitle = dialog.findViewById(R.id.downloadSubtitle);
        TextView files = dialog.findViewById(R.id.downloadFiles);
        TextView cancel = dialog.findViewById(R.id.cancelButton);

        subtitle.setText("AIQuizLite v" + info.versionName);
        cancel.setOnClickListener(v -> {
            cancelFlag.set(true);
            Toast.makeText(activity, "已取消更新下载", Toast.LENGTH_SHORT).show();
        });
        dialog.setOnCancelListener(d -> cancelFlag.set(true));
        dialog.show();

        UpdateChecker.downloadAndInstall(
                activity,
                serverUrl,
                info,
                (percent, downloaded, total) -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (percent < 0) {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        return;
                    }
                    ring.setProgress(percent);
                    if (total > 0) {
                        files.setText("已下载 " + formatSize(downloaded) + " / 共 " + formatSize(total));
                    } else {
                        files.setText("已下载 " + formatSize(downloaded));
                    }
                },
                () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                },
                cancelFlag
        );
    }

    private static void styleDialog(Dialog dialog, float widthRatio) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams lp = window.getAttributes();
        DisplayMetrics metrics = dialog.getContext().getResources().getDisplayMetrics();
        lp.width = (int) (metrics.widthPixels * widthRatio);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(lp);
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / (1024f * 1024f));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
