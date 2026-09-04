package com.example.aiquizlite;

import java.util.Collection;

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
        String[] parts = name.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("ZIP包含不安全路径");
            }
        }
        if (!"question-bank.json".equals(name)
                && !(name.startsWith("images/") && parts.length == 2)) {
            throw new IllegalArgumentException("ZIP包含不支持的文件：" + name);
        }
        return name;
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
