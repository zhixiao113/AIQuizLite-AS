package com.example.aiquizlite;

import org.json.JSONObject;

/** Validated metadata bound to the source used for this check, not later settings changes. */
final class UpdateManifest {
    final String source;
    final int versionCode;
    final String versionName;
    final String apkFile;
    final String apkUrl;
    final String apkSha256;
    final long apkSize;
    final String notes;

    private UpdateManifest(String source, JSONObject json) throws Exception {
        this.source = UpdateSourceRules.normalize(source);
        UpdateSourceRules.manifestUrl(source);
        versionCode = strictNumber(json, "versionCode", Integer.MAX_VALUE).intValue();
        versionName = string(json, "versionName", 80);
        apkSha256 = string(json, "apkSha256", 64).toLowerCase(java.util.Locale.ROOT);
        if (!apkSha256.matches("[0-9a-f]{64}")) { throw invalid(); }
        if (!"com.example.aiquizlite".equals(string(json, "packageName", 100))
                || !UpdateSourceRules.SIGNER.equals(string(json, "signerSha256", 64))) { throw invalid(); }
        boolean github = UpdateSourceRules.isGithub(source);
        if (github) {
            if (strictNumber(json, "schemaVersion", 1).intValue() != 1) { throw invalid(); }
            apkUrl = string(json, "apkUrl", 2048);
            UpdateSourceRules.validateGithubApk(apkUrl);
            apkFile = "";
            apkSize = strictNumber(json, "apkSize", UpdateSourceRules.MAX_APK_BYTES).longValue();
        } else {
            apkFile = string(json, "apkFile", 200);
            apkUrl = UpdateSourceRules.localApkUrl(source, apkFile);
            apkSize = json.has("apkSize")
                    ? strictNumber(json, "apkSize", UpdateSourceRules.MAX_APK_BYTES).longValue() : 0;
        }
        Object note = json.opt("notes");
        if (note != null && note != JSONObject.NULL && !(note instanceof String)) { throw invalid(); }
        notes = note instanceof String ? (String) note : "";
        if (notes.length() > 16000) { throw invalid(); }
    }

    static UpdateManifest parse(String source, String body) throws Exception {
        if (body == null || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > UpdateSourceRules.MAX_MANIFEST_BYTES) { throw invalid(); }
        return new UpdateManifest(source, new JSONObject(body));
    }

    private static Long strictNumber(JSONObject json, String key, long max) {
        Object value = json.opt(key);
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double) { throw invalid(); }
        long number;
        try { number = Long.parseLong(value.toString()); }
        catch (NumberFormatException error) { throw invalid(); }
        if (number <= 0 || number > max) { throw invalid(); }
        return number;
    }

    private static String string(JSONObject json, String key, int limit) {
        Object value = json.opt(key);
        if (!(value instanceof String)) { throw invalid(); }
        String text = (String) value;
        if (text.trim().isEmpty() || text.length() > limit) { throw invalid(); }
        return text;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("更新清单不完整或不合法，已停止检查。");
    }
}
