package com.example.aiquizlite;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Collection;

public final class FullBackupRules {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRY_COUNT = 10_000;
    public static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;

    private FullBackupRules() {
    }

    public static String fileName(long timestamp) {
        return "AIQuizLite-backup-"
                + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
                .format(new Date(timestamp))
                + ".zip";
    }

    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\")) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.contains("../") || normalized.equals("..") || normalized.contains(":")) {
            return false;
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..") || part.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static String safeFileName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")
                || trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("备份中包含非法文件名");
        }
        return trimmed;
    }

    public static String uniqueFileName(String preferred, Collection<String> existing) {
        if (!existing.contains(preferred)) return preferred;
        String base = preferred.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? preferred.substring(0, preferred.length() - 4)
                : preferred;
        for (int index = 2; index < 10_000; index++) {
            String candidate = base + "-" + index + ".zip";
            if (!existing.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("无法生成唯一的备份文件名");
    }
}
