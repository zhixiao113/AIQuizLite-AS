package com.example.aiquizlite;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class UpdateChecker {
    /** Public metadata channel; APKs use fixed-tag URLs supplied by a validated manifest. */
    public static final String DEFAULT_SERVER_URL = UpdateSourceRules.GITHUB_MANIFEST;

    public interface Callback<T> {
        void onResult(T result);

        void onError(String message);
    }

    public interface ProgressListener {
        /**
         * @param percent    0~100；小于 0 表示下载结束（取消或失败）
         * @param downloaded 已下载字节数
         * @param total      总字节数（未知时为 0）
         */
        void onProgress(int percent, long downloaded, long total);
    }

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkFile;
        public final String apkSha256;
        public final String notes;
        public final String source;
        public final String apkUrl;
        public final long apkSize;

        UpdateInfo(UpdateManifest manifest) {
            versionCode = manifest.versionCode;
            versionName = manifest.versionName;
            apkFile = manifest.apkFile;
            apkSha256 = manifest.apkSha256;
            notes = manifest.notes;
            source = manifest.source;
            apkUrl = manifest.apkUrl;
            apkSize = manifest.apkSize;
        }
    }

    private UpdateChecker() {
    }

    public static boolean hasNewVersion(Context context, UpdateInfo info) {
        return info != null && info.versionCode > BuildConfig.VERSION_CODE;
    }

    public static String normalizeBaseUrl(String serverUrl) {
        return UpdateSourceRules.normalize(serverUrl);
    }

    public static void checkUpdate(Context context, String serverUrl, Callback<UpdateInfo> callback) {
        final String base = normalizeBaseUrl(serverUrl);
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = UpdateTransport.open(base, UpdateSourceRules.manifestUrl(base), null);
                String body = readBody(connection);
                UpdateInfo info = new UpdateInfo(UpdateManifest.parse(base, body));
                postResult(context, callback, info);
            } catch (Exception error) {
                postError(context, callback, friendlyError(error));
            } finally {
                if (connection != null) { connection.disconnect(); }
            }
        });
        thread.start();
    }

    public static void downloadAndInstall(
            Activity activity,
            String serverUrl,
            UpdateInfo info,
            ProgressListener progress,
            Runnable onReadyToInstall,
            java.util.concurrent.atomic.AtomicBoolean cancelFlag
    ) {
        if (!canInstall(activity)) {
            if (progress != null) {
                progress.onProgress(-1, 0, 0);
            }
            promptEnableUnknownSources(activity);
            return;
        }
        Thread thread = new Thread(() -> {
            File apkFile = null;
            HttpURLConnection connection = null;
            boolean handedToInstaller = false;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    postError(activity, progress, "无法创建下载目录");
                    return;
                }
                apkFile = File.createTempFile("app-update-" + info.versionCode + "-", ".apk", dir);
                connection = UpdateTransport.open(info.source, info.apkUrl, cancelFlag);
                boolean cancelled = false;
                long total = connection.getContentLengthLong();
                if (total > UpdateSourceRules.MAX_APK_BYTES || (info.apkSize > 0 && total > 0
                        && total != info.apkSize)) { throw new IllegalArgumentException("更新包大小不符"); }
                long downloaded = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (cancelFlag != null && cancelFlag.get()) {
                            cancelled = true;
                            break;
                        }
                        output.write(buffer, 0, read);
                        downloaded += read;
                        if (downloaded > UpdateSourceRules.MAX_APK_BYTES
                                || (info.apkSize > 0 && downloaded > info.apkSize)) {
                            throw new IllegalArgumentException("更新包超过允许大小");
                        }
                        if (progress != null) {
                            final int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
                            final long done = downloaded;
                            final long all = total;
                            activity.runOnUiThread(() -> progress.onProgress(percent, done, all));
                        }
                    }
                }
                if (cancelled) {
                    if (!apkFile.delete()) {
                        apkFile.deleteOnExit();
                    }
                    activity.runOnUiThread(() -> {
                        if (progress != null) {
                            progress.onProgress(-1, 0, 0);
                        }
                    });
                    return;
                }
                if (downloaded <= 0 || (info.apkSize > 0 && downloaded != info.apkSize)
                        || (total > 0 && downloaded != total)) { throw new IllegalArgumentException("更新包下载不完整"); }
                {
                    String actual = sha256Of(apkFile);
                    if (!actual.equalsIgnoreCase(info.apkSha256)) {
                        if (!apkFile.delete()) {
                            apkFile.deleteOnExit();
                        }
                        postError(activity, progress, "下载文件校验失败，请稍后重试");
                        return;
                    }
                }
                validateApk(activity, apkFile, info);
                final File readyApk = apkFile;
                activity.runOnUiThread(() -> {
                    if ((cancelFlag != null && cancelFlag.get()) || activity.isFinishing() || activity.isDestroyed()) {
                        readyApk.delete();
                        if (progress != null) {
                            progress.onProgress(-1, 0, 0);
                        }
                        return;
                    }
                    if (onReadyToInstall != null) {
                        onReadyToInstall.run();
                    }
                    installApk(activity, readyApk);
                });
                handedToInstaller = true;
            } catch (Exception error) {
                if (cancelFlag == null || !cancelFlag.get()) {
                    postError(activity, progress, friendlyError(error));
                } else { activity.runOnUiThread(() -> {
                    if (progress != null) { progress.onProgress(-1, 0, 0); }
                }); }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (!handedToInstaller && apkFile != null && apkFile.exists()) {
                    apkFile.delete();
                }
            }
        });
        thread.start();
    }

    @SuppressWarnings("deprecation")
    private static void validateApk(Context context, File apkFile, UpdateInfo info) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = manager.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
        if (candidate == null) {
            throw new IllegalArgumentException("更新包损坏或无法解析，请重新下载。");
        }
        long installedCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? installed.getLongVersionCode() : installed.versionCode;
        long candidateCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? candidate.getLongVersionCode() : candidate.versionCode;
        String error = UpdatePackageRules.validationError(installed.packageName, installedCode,
                candidate.packageName, candidateCode, candidate.versionName, info.versionCode,
                info.versionName, signerDigests(installed), signerDigests(candidate));
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        if (!signerDigests(candidate).equals(java.util.Collections.singleton(UpdateSourceRules.SIGNER))) {
            throw new IllegalArgumentException("更新包不是原证书正式签名，已停止安装。");
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> digests = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) {
                    hex.append(String.format("%02x", value));
                }
                digests.add(hex.toString());
            }
        }
        return digests;
    }

    private static boolean canInstall(Activity activity) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void promptEnableUnknownSources(Activity activity) {
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("在线安装新版本需要允许安装未知应用。\n请在系统设置中为 AIQuizLite 开启该权限后再试。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("去开启", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())
                        );
                        activity.startActivity(intent);
                    } catch (ActivityNotFoundException ignored) {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                        } catch (ActivityNotFoundException ignoredAgain) {
                            android.widget.Toast.makeText(
                                    activity,
                                    "请在系统设置中允许 AIQuizLite 安装未知应用",
                                    android.widget.Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                })
                .show();
    }

    private static void installApk(Activity activity, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception error) {
            android.widget.Toast.makeText(
                    activity,
                    "无法打开安装界面：" + friendlyError(error),
                    android.widget.Toast.LENGTH_LONG
            ).show();
        }
    }

    private static String sha256Of(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        try (InputStream input = connection.getInputStream();
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (buffer.size() + read > UpdateSourceRules.MAX_MANIFEST_BYTES) {
                    throw new IllegalArgumentException("更新清单过大，已停止检查。");
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String friendlyError(Exception error) {
        if (error instanceof javax.net.ssl.SSLException) { return "HTTPS 证书验证失败，未绕过安全检查。"; }
        if (error instanceof java.net.SocketTimeoutException) { return "连接超时，请检查网络后重试。"; }
        if (error instanceof java.net.UnknownHostException) { return "无法连接更新源，请检查网络或手动选择备用源。"; }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "连接失败";
        }
        return message;
    }

    private static void postResult(Context context, Callback<UpdateInfo> callback, UpdateInfo info) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(() -> callback.onResult(info));
        } else {
            callback.onResult(info);
        }
    }

    private static void postError(Context context, Callback<UpdateInfo> callback, String message) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(() -> callback.onError(message));
        } else {
            callback.onError(message);
        }
    }

    private static void postError(Activity activity, ProgressListener progress, String message) {
        activity.runOnUiThread(() -> {
            if (progress != null) {
                progress.onProgress(-1, 0, 0);
            }
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("更新未完成")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }
}
