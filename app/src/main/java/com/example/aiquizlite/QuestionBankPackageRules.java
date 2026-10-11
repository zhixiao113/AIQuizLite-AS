package com.example.aiquizlite;

import java.util.Collection;
import java.util.Locale;

final class QuestionBankPackageRules {
    private QuestionBankPackageRules() {
    }

    static String uniqueDisplayName(String requested, Collection<String> existingNames) {
        String base = requested == null ? "" : requested.trim();
        if (base.isEmpty()) {
            base = "导入题库";
        }
        if (!existingNames.contains(base)) {
            return base;
        }
        String candidate = base + "（副本）";
        if (!existingNames.contains(candidate)) {
            return candidate;
        }
        int index = 2;
        while (existingNames.contains(base + "（副本 " + index + "）")) {
            index++;
        }
        return base + "（副本 " + index + "）";
    }

    static String validateEntryName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("ZIP包含空路径");
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("ZIP包含绝对路径");
        }
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                    || part.indexOf(':') >= 0 || part.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("ZIP包含不安全路径");
            }
        }
        boolean json = name.toLowerCase(Locale.ROOT).endsWith(".json")
                && parts.length <= 2;
        if (!json && !isImageEntry(name)) {
            throw new IllegalArgumentException("ZIP 里有多余文件「" + name
                    + "」。一个题库包只需要一个 JSON 文件，图片放在 image 或 images 文件夹内，请删除多余文件后重新打包。");
        }
        return name;
    }

    /**
     * macOS / Windows 压缩包常见的系统垃圾文件，导入时静默跳过，不视为题库内容。
     */
    static boolean isSystemJunkEntry(String rawName) {
        if (rawName == null) {
            return false;
        }
        String normalized = rawName.replace('\\', '/');
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if ("__MACOSX".equals(part)) {
                return true;
            }
            if (".DS_Store".equals(part)) {
                return true;
            }
            if ("Thumbs.db".equalsIgnoreCase(part) || "desktop.ini".equalsIgnoreCase(part)) {
                return true;
            }
            if (part.startsWith("._")) {
                return true;
            }
        }
        return false;
    }

    static boolean isImageEntry(String name) {
        String[] parts = name.split("/");
        return (parts.length >= 2 && isImageDirectory(parts[0]))
                || (parts.length >= 3 && isImageDirectory(parts[1]));
    }

    private static boolean isImageDirectory(String name) {
        return "image".equals(name) || "images".equals(name);
    }

    static String exportImageName(int index, String imageReference) {
        String displayName = QuestionImageStore.displayName(imageReference);
        int dot = displayName.lastIndexOf('.');
        String extension = dot >= 0 ? displayName.substring(dot).toLowerCase() : ".jpg";
        if (!extension.matches("\\.(jpg|jpeg|png|webp)")) {
            extension = ".jpg";
        }
        return String.format("image_%03d%s", index, extension);
    }
}
