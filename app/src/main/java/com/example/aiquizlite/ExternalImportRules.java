package com.example.aiquizlite;

import java.util.Locale;

final class ExternalImportRules {
    enum Destination {
        QUESTION_BANK_JSON,
        MATERIAL,
        LEGACY_WORD_UNSUPPORTED,
        UNSUPPORTED
    }

    private ExternalImportRules() {
    }

    static Destination resolve(
            String mimeType,
            String displayName,
            boolean hasUri,
            CharSequence sharedText
    ) {
        String name = normalize(displayName);
        String mime = normalizeMime(mimeType);

        if (name.endsWith(".json")) {
            return Destination.QUESTION_BANK_JSON;
        }
        if (name.endsWith(".txt") || name.endsWith(".pdf") || name.endsWith(".docx")) {
            return Destination.MATERIAL;
        }
        if (name.endsWith(".doc")) {
            return Destination.LEGACY_WORD_UNSUPPORTED;
        }

        if ("application/json".equals(mime) || "text/json".equals(mime)) {
            return Destination.QUESTION_BANK_JSON;
        }
        if ("application/pdf".equals(mime)
                || "application/x-pdf".equals(mime)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
            return Destination.MATERIAL;
        }

        // 微信分享一段 JSON 文本时通常没有文件 URI，只会附带 EXTRA_TEXT。
        if (!hasUri && sharedText != null && sharedText.length() > 0) {
            return Destination.QUESTION_BANK_JSON;
        }
        if (hasUri && "text/plain".equals(mime)) {
            return Destination.MATERIAL;
        }

        return Destination.UNSUPPORTED;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMime(String value) {
        String mime = normalize(value);
        int separator = mime.indexOf(';');
        return separator >= 0 ? mime.substring(0, separator).trim() : mime;
    }
}
