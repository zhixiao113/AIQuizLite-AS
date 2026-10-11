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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class UpdateChecker {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    /**
     * 内置更新服务器地址（SakuraFrp 内网穿透 + 自动 HTTPS）。
     * 隧道 mcMC：公网 https://frp-dad.com:41717 → 本地 127.0.0.1:4387。
     * 若 SakuraFrp 域名变更，替换为隧道卡片上显示的地址，末尾不要带斜杠。
     */
    public static final String DEFAULT_SERVER_URL = "https://frp-dad.com:41717";

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

        UpdateInfo(int versionCode, String versionName, String apkFile, String apkSha256, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.apkFile = apkFile == null ? "" : apkFile;
            this.apkSha256 = apkSha256 == null ? "" : apkSha256;
            this.notes = notes == null ? "" : notes;
        }
    }

    private UpdateChecker() {
    }

    public static boolean hasNewVersion(Context context, UpdateInfo info) {
        return info != null && info.versionCode > BuildConfig.VERSION_CODE;
    }

    public static String normalizeBaseUrl(String serverUrl) {
        String base = serverUrl == null ? "" : serverUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    public static void checkUpdate(Context context, String serverUrl, Callback<UpdateInfo> callback) {
        final String base = normalizeBaseUrl(serverUrl);
        Thread thread = new Thread(() -> {
            try {
                HttpURLConnection connection = openConnection(base + "/version.json");
                int code = connection.getResponseCode();
                if (code != 200) {
                    postError(context, callback, "服务器返回状态码 " + code);
                    return;
                }
                String body = readBody(connection);
                JSONObject json = new JSONObject(body);
                UpdateInfo info = new UpdateInfo(
                        json.optInt("versionCode", 0),
                        json.optString("versionName", ""),
                        json.optString("apkFile", ""),
                        json.optString("apkSha256", ""),
                        json.optString("notes", "")
                );
                if (info.versionCode <= 0 || info.apkFile.isEmpty()) {
                    postError(context, callback, "服务器版本信息不完整");
                    return;
                }
                postResult(context, callback, info);
            } catch (Exception error) {
                postError(context, callback, friendlyError(error));
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
        final String base = normalizeBaseUrl(serverUrl);
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
                connection = openConnection(base + "/" + info.apkFile);
                int code = connection.getResponseCode();
                if (code != 200) {
                    postError(activity, progress, "下载失败，服务器返回状态码 " + code);
                    return;
                }
                boolean cancelled = false;
                long total = connection.getContentLengthLong();
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (cancelFlag != null && cancelFlag.get()) {
                            cancelled = true;
                            break;
                        }
                        output.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0 && progress != null) {
                            final int percent = (int) (downloaded * 100 / total);
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
                if (info.apkSha256 != null && !info.apkSha256.isEmpty()) {
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
                postError(activity, progress, friendlyError(error));
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

    private static HttpURLConnection openConnection(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            SSLSocketFactory factory = sakuraSocketFactory();
            if (factory != null) {
                https.setSSLSocketFactory(factory);
            }
            https.setHostnameVerifier((hostname, session) -> true);
        }
        return connection;
    }

    /**
     * SakuraFrp 自动 HTTPS 使用自签/内部证书，Android 默认不信任。
     * 这里信任 SakuraFrp 节点证书（仅用于本 App 的更新下载），
     * 同时配合 version.json 中的 APK SHA-256 校验防止文件被篡改。
     */
    private static SSLSocketFactory sakuraSocketFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context.getSocketFactory();
        } catch (Exception error) {
            return null;
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
        InputStream input = connection.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        input.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String friendlyError(Exception error) {
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
