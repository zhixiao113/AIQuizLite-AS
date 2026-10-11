package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExternalImportRulesTest {
    @Test
    public void jsonFileUsesQuestionBankImporterEvenWhenWechatReportsPlainText() {
        assertEquals(
                ExternalImportRules.Destination.QUESTION_BANK_JSON,
                ExternalImportRules.resolve("text/plain", "期末题库.JSON", true, null)
        );
    }

    @Test
    public void sharedJsonTextWithoutAFileUsesQuestionBankImporter() {
        assertEquals(
                ExternalImportRules.Destination.QUESTION_BANK_JSON,
                ExternalImportRules.resolve("text/plain", "", false, "[{\"stem\":\"题目\"}]")
        );
    }

    @Test
    public void txtPdfAndDocxFilesUseMaterialWorkspace() {
        assertEquals(
                ExternalImportRules.Destination.MATERIAL,
                ExternalImportRules.resolve("text/plain", "讲义.txt", true, null)
        );
        assertEquals(
                ExternalImportRules.Destination.MATERIAL,
                ExternalImportRules.resolve("application/octet-stream", "讲义.pdf", true, null)
        );
        assertEquals(
                ExternalImportRules.Destination.MATERIAL,
                ExternalImportRules.resolve("application/msword", "讲义.docx", true, null)
        );
    }

    @Test
    public void mimeTypeCanRouteMaterialWhenWechatHidesTheFileName() {
        assertEquals(
                ExternalImportRules.Destination.MATERIAL,
                ExternalImportRules.resolve("application/pdf; charset=binary", "", true, null)
        );
        assertEquals(
                ExternalImportRules.Destination.MATERIAL,
                ExternalImportRules.resolve("text/plain", "", true, null)
        );
    }

    @Test
    public void legacyDocIsNotAccepted() {
        assertEquals(
                ExternalImportRules.Destination.LEGACY_WORD_UNSUPPORTED,
                ExternalImportRules.resolve("application/msword", "旧讲义.doc", true, null)
        );
    }

    @Test
    public void zipFilesUseQuestionBankImporterAndAreValidatedThere() {
        assertEquals(ExternalImportRules.Destination.QUESTION_BANK_ZIP,
                ExternalImportRules.resolve("application/octet-stream", "图文题库.ZIP", true, null));
        assertEquals(ExternalImportRules.Destination.QUESTION_BANK_ZIP,
                ExternalImportRules.resolve("application/zip; charset=binary", "", true, null));
        assertEquals(ExternalImportRules.Destination.QUESTION_BANK_ZIP,
                ExternalImportRules.resolve("application/x-zip-compressed", "", true, null));
        assertEquals(ExternalImportRules.Destination.MATERIAL,
                ExternalImportRules.resolve("application/zip", "讲义.docx", true, null));
    }
}
