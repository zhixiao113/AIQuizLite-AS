package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownloadArchiveRulesTest {
    @Test
    public void uniqueFileNameKeepsUnusedName() {
        assertEquals(
                "信息安全.zip",
                DownloadArchiveRules.uniqueFileName("信息安全.zip", Collections.emptyList())
        );
    }

    @Test
    public void uniqueFileNameAddsIncreasingChineseIndex() {
        assertEquals(
                "信息安全（3）.zip",
                DownloadArchiveRules.uniqueFileName(
                        "信息安全.zip",
                        Arrays.asList("信息安全.zip", "信息安全（2）.zip")
                )
        );
    }

    @Test
    public void uniqueFileNameAddsZipExtension() {
        assertEquals(
                "题库.zip",
                DownloadArchiveRules.uniqueFileName("题库", Collections.emptyList())
        );
    }

    @Test
    public void isTodayUsesLocalCalendarDayInsteadOfElapsedHours() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        long now = 1_720_028_700_000L;
        assertTrue(DownloadArchiveRules.isToday(now - 60_000L, now, zone));
        assertFalse(DownloadArchiveRules.isToday(now - 24L * 60L * 60L * 1000L, now, zone));
    }

    @Test
    public void sameSourceBankIdReplacesExistingArchive() {
        assertTrue(DownloadArchiveRules.shouldReplace(
                "bank-1",
                "bank-1",
                "新名称.zip",
                "旧名称.zip"
        ));
    }

    @Test
    public void differentSourceBankIdsNeverReplaceEvenWhenNamesMatch() {
        assertFalse(DownloadArchiveRules.shouldReplace(
                "bank-2",
                "bank-1",
                "同名题库.zip",
                "同名题库.zip"
        ));
    }

    @Test
    public void legacyRecordWithoutSourceIdIsClaimedByExactFileName() {
        assertTrue(DownloadArchiveRules.shouldReplace(
                "bank-1",
                null,
                "信息安全.zip",
                "信息安全.zip"
        ));
        assertFalse(DownloadArchiveRules.shouldReplace(
                "bank-1",
                null,
                "信息安全.zip",
                "信息安全（2）.zip"
        ));
    }
}
