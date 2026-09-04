package com.example.aiquizlite;

import java.util.Calendar;
import java.util.Collection;
import java.util.Locale;
import java.util.TimeZone;

final class DownloadArchiveRules {
    private DownloadArchiveRules() {
    }

    static String uniqueFileName(String requested, Collection<String> existingNames) {
        String safe = requested == null ? "" : requested.trim();
        if (safe.isEmpty()) {
            safe = "题库.zip";
        }
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            safe += ".zip";
        }
        if (!existingNames.contains(safe)) {
            return safe;
        }
        String base = safe.substring(0, safe.length() - 4);
        int index = 2;
        while (existingNames.contains(base + "（" + index + "）.zip")) {
            index++;
        }
        return base + "（" + index + "）.zip";
    }

    static boolean shouldReplace(
            String requestedSourceBankId,
            String existingSourceBankId,
            String requestedDisplayName,
            String existingDisplayName
    ) {
        String requestedId = normalizeId(requestedSourceBankId);
        String existingId = normalizeId(existingSourceBankId);
        if (!requestedId.isEmpty() && !existingId.isEmpty()) {
            return requestedId.equals(existingId);
        }
        return !requestedId.isEmpty()
                && existingId.isEmpty()
                && normalizeName(requestedDisplayName).equals(normalizeName(existingDisplayName));
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }

    static boolean isToday(long timestamp, long now, TimeZone timeZone) {
        Calendar value = Calendar.getInstance(timeZone);
        value.setTimeInMillis(timestamp);
        Calendar current = Calendar.getInstance(timeZone);
        current.setTimeInMillis(now);
        return value.get(Calendar.ERA) == current.get(Calendar.ERA)
                && value.get(Calendar.YEAR) == current.get(Calendar.YEAR)
                && value.get(Calendar.DAY_OF_YEAR) == current.get(Calendar.DAY_OF_YEAR);
    }
}
