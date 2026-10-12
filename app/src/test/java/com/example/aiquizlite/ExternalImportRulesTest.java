package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

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
    public void txtPdfAndDocxFilesAreUnsupported() {
        assertEquals(
                ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("text/plain", "讲义.txt", true, null)
        );
        assertEquals(
                ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("application/octet-stream", "讲义.pdf", true, null)
        );
        assertEquals(
                ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("application/msword", "讲义.docx", true, null)
        );
    }

    @Test
    public void hiddenNamePdfIsRejectedButGenericTextRequiresLocalSniffing() {
        assertEquals(
                ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("application/pdf; charset=binary", "", true, null)
        );
        assertEquals(
                ExternalImportRules.Destination.QUESTION_BANK_AUTO,
                ExternalImportRules.resolve("text/plain", "", true, null)
        );
    }

    @Test
    public void legacyDocIsNotAccepted() {
        assertEquals(
                ExternalImportRules.Destination.UNSUPPORTED,
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
        assertEquals(ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("application/zip", "讲义.docx", true, null));
    }

    @Test public void namedDocumentsCannotBypassRejectionWithBankMimeTypes() {
        for (String name : new String[]{"讲义.txt", "讲义.pdf", "讲义.doc", "讲义.docx", "图片.png"}) {
            for (String mime : new String[]{"application/json", "application/zip", "text/plain", "application/octet-stream"}) {
                assertEquals(name + " / " + mime, ExternalImportRules.Destination.UNSUPPORTED,
                        ExternalImportRules.resolve(mime, name, true, null));
            }
        }
    }

    @Test public void readyBankExtensionWinsOverIncorrectProviderMime() {
        assertEquals(ExternalImportRules.Destination.QUESTION_BANK_JSON,
                ExternalImportRules.resolve("application/octet-stream", "题库.JSON", true, null));
        assertEquals(ExternalImportRules.Destination.QUESTION_BANK_ZIP,
                ExternalImportRules.resolve("text/plain", "题库.zip", true, null));
    }

    @Test public void genericUriWithoutExtensionUsesLocalContentDetection() {
        for (String mime : new String[]{"application/octet-stream", "text/plain", "", null}) {
            assertEquals(ExternalImportRules.Destination.QUESTION_BANK_AUTO,
                    ExternalImportRules.resolve(mime, "123456", true, null));
        }
    }

    @Test public void unnamedWordAndImageMimeTypesRemainUnsupported() {
        for (String mime : new String[]{"application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/png"}) {
            assertEquals(ExternalImportRules.Destination.UNSUPPORTED,
                    ExternalImportRules.resolve(mime, "", true, null));
        }
    }

    @Test public void sharedProseIsNotDocumentGeneration() {
        assertEquals(ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("text/plain", "", false, "这是一份学习资料，请自动出题"));
        assertEquals(ExternalImportRules.Destination.UNSUPPORTED,
                ExternalImportRules.resolve("application/json", "", false, null));
    }

    @Test public void sharedJsonWithBomOrMarkdownStillRoutesToLocalImporter() {
        for (String text : new String[]{"\uFEFF [ ]", "{\"questions\":[]}", "```json\n[]\n```"}) {
            assertEquals(ExternalImportRules.Destination.QUESTION_BANK_JSON,
                    ExternalImportRules.resolve("text/plain", "", false, text));
        }
    }

    @Test public void sniffingOnlySelectsZipWhenMetadataIsUnknown() {
        assertTrue(ExternalImportRules.shouldReadZip(ExternalImportRules.Destination.QUESTION_BANK_AUTO, 'P', 'K'));
        assertFalse(ExternalImportRules.shouldReadZip(ExternalImportRules.Destination.QUESTION_BANK_AUTO, '[', '{'));
        assertFalse(ExternalImportRules.shouldReadZip(ExternalImportRules.Destination.QUESTION_BANK_JSON, 'P', 'K'));
        assertTrue(ExternalImportRules.shouldReadZip(ExternalImportRules.Destination.QUESTION_BANK_ZIP, '[', '{'));
    }

    @Test public void unsupportedFormatCannotProceedToReading() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalImportRules.shouldReadZip(ExternalImportRules.Destination.UNSUPPORTED, 'P', 'K'));
    }

    @Test public void previewBankNameStripsOnlyBankExtensionAfterValidation() {
        assertEquals("期末题库", ExternalImportRules.bankNameFromFile("期末题库.JSON"));
        assertEquals("图文题库", ExternalImportRules.bankNameFromFile("图文题库.zip"));
        assertEquals("讲义.txt", ExternalImportRules.bankNameFromFile("讲义.txt"));
        assertEquals("自定义题库", ExternalImportRules.bankNameFromFile(null));
    }
}
