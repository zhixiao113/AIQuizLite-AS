package com.example.aiquizlite;

import org.junit.Test;

import java.util.TimeZone;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FullBackupRulesTest {
    @Test
    public void fileNameUsesStableSortableTimestamp() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals(
                    "AIQuizLite-backup-2024-07-04-075000.zip",
                    FullBackupRules.fileName(1_720_050_600_000L)
            );
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void safeZipEntryAcceptsNestedApplicationFiles() {
        assertTrue(FullBackupRules.isSafeEntryName(
                "files/question_bank_images/imported_1/img_1.jpg"
        ));
        assertTrue(FullBackupRules.isSafeEntryName("manifest.json"));
    }

    @Test
    public void safeZipEntryRejectsTraversalAndAbsolutePaths() {
        assertFalse(FullBackupRules.isSafeEntryName("../shared_prefs/api.xml"));
        assertFalse(FullBackupRules.isSafeEntryName("files/../../api.xml"));
        assertFalse(FullBackupRules.isSafeEntryName("/data/data/api.xml"));
        assertFalse(FullBackupRules.isSafeEntryName("C:\\api.xml"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void safeFileNameRejectsNestedPath() {
        FullBackupRules.safeFileName("../questions.json");
    }

    @Test
    public void safeFileNameKeepsNormalUnicodeName() {
        assertEquals("信息安全题库.json", FullBackupRules.safeFileName("信息安全题库.json"));
    }

    @Test
    public void uniqueBackupNameDoesNotOverwriteSameSecondArchive() {
        assertEquals(
                "AIQuizLite-backup-2024-07-04-075000-3.zip",
                FullBackupRules.uniqueFileName(
                        "AIQuizLite-backup-2024-07-04-075000.zip",
                        Arrays.asList(
                                "AIQuizLite-backup-2024-07-04-075000.zip",
                                "AIQuizLite-backup-2024-07-04-075000-2.zip"
                        )
                )
        );
    }
}
