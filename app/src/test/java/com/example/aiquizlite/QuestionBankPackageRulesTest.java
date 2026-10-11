package com.example.aiquizlite;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestionBankPackageRulesTest {
    @Test
    public void sameNameImportCreatesNumberedCopyWithoutOverwrite() {
        assertEquals(
                "网络安全（副本 3）",
                QuestionBankPackageRules.uniqueDisplayName(
                        "网络安全",
                        Arrays.asList("网络安全", "网络安全（副本）", "网络安全（副本 2）")
                )
        );
    }

    @Test
    public void unusedNameIsPreserved() {
        assertEquals(
                "新题库",
                QuestionBankPackageRules.uniqueDisplayName("新题库", Collections.singleton("旧题库"))
        );
    }

    @Test
    public void acceptsJsonWithEitherImageDirectoryAndAnOptionalWrapperFolder() {
        assertEquals("question-bank.json", QuestionBankPackageRules.validateEntryName("question-bank.json"));
        assertEquals("images/a.jpg", QuestionBankPackageRules.validateEntryName("images/a.jpg"));
        assertEquals("题库.json", QuestionBankPackageRules.validateEntryName("题库.json"));
        assertEquals("image/a.png", QuestionBankPackageRules.validateEntryName("image\\a.png"));
        assertEquals("题库/image/章节/图.png", QuestionBankPackageRules.validateEntryName("题库/image/章节/图.png"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZipSlipPath() {
        QuestionBankPackageRules.validateEntryName("images/../../secret.txt");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnexpectedZipContent() {
        QuestionBankPackageRules.validateEntryName("notes.txt");
    }

    @Test
    public void exportedImageNamesAreDeterministicAndSafe() {
        assertEquals(
                "image_007.png",
                QuestionBankPackageRules.exportImageName(7, "local:unsafe name.png")
        );
    }

    @Test
    public void recognizesSystemJunkFiles() {
        assertTrue(QuestionBankPackageRules.isSystemJunkEntry(".DS_Store"));
        assertTrue(QuestionBankPackageRules.isSystemJunkEntry("__MACOSX/question.json"));
        assertTrue(QuestionBankPackageRules.isSystemJunkEntry("images/.DS_Store"));
        assertTrue(QuestionBankPackageRules.isSystemJunkEntry("Thumbs.db"));
        assertTrue(QuestionBankPackageRules.isSystemJunkEntry("._question.json"));
        assertTrue(QuestionBankPackageRules.isSystemJunkEntry("desktop.ini"));
        assertFalse(QuestionBankPackageRules.isSystemJunkEntry("question-bank.json"));
        assertFalse(QuestionBankPackageRules.isSystemJunkEntry("images/a.jpg"));
        assertFalse(QuestionBankPackageRules.isSystemJunkEntry(null));
    }
}
