package com.example.aiquizlite;

import java.util.Locale;

final class ExternalImportRules {
    enum Destination {
        QUESTION_BANK_JSON,
        QUESTION_BANK_ZIP,
        QUESTION_BANK_AUTO,
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

        if (!hasUri) {
            String text = sharedText == null ? "" : sharedText.toString().trim();
            if (text.startsWith("\uFEFF")) { text = text.substring(1).trim(); }
            return text.startsWith("[") || text.startsWith("{") || text.startsWith("```")
                    ? Destination.QUESTION_BANK_JSON : Destination.UNSUPPORTED;
        }
        if (name.endsWith(".json")) {
            return Destination.QUESTION_BANK_JSON;
        }
        if (name.endsWith(".zip")) {
            return Destination.QUESTION_BANK_ZIP;
        }
        // A named non-bank file must not become a ZIP/JSON merely because a provider
        // reports an inaccurate MIME type (DOCX, for example, is itself a ZIP container).
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int extension = name.lastIndexOf('.');
        if (extension > separator && extension < name.length() - 1) {
            return Destination.UNSUPPORTED;
        }

        if ("application/json".equals(mime) || "text/json".equals(mime)) {
            return Destination.QUESTION_BANK_JSON;
        }
        if ("application/zip".equals(mime) || "application/x-zip-compressed".equals(mime)) {
            return Destination.QUESTION_BANK_ZIP;
        }
        // Some WeChat/file providers omit an extension and report only a generic type.
        // The local importer must sniff and validate bounded JSON/ZIP contents, never use AI.
        if ("application/octet-stream".equals(mime) || "text/plain".equals(mime) || mime.isEmpty()) {
            return Destination.QUESTION_BANK_AUTO;
        }

        return Destination.UNSUPPORTED;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean shouldReadZip(Destination format, int first, int second) {
        if (format == null || format == Destination.UNSUPPORTED) {
            throw new IllegalArgumentException("仅支持 JSON / ZIP 题库");
        }
        return format == Destination.QUESTION_BANK_ZIP
                || (format == Destination.QUESTION_BANK_AUTO && first == 'P' && second == 'K');
    }

    static String bankNameFromFile(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) { name = name.substring(0, name.length() - 5); }
        else if (lower.endsWith(".zip")) { name = name.substring(0, name.length() - 4); }
        return name.isEmpty() ? "自定义题库" : name;
    }

    private static String normalizeMime(String value) {
        String mime = normalize(value);
        int separator = mime.indexOf(';');
        return separator >= 0 ? mime.substring(0, separator).trim() : mime;
    }
}
