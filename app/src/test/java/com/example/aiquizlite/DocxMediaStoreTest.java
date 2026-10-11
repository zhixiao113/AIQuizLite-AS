package com.example.aiquizlite;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class DocxMediaStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void listsAndReadsOnlySafeEmbeddedPictures() throws Exception {
        File document = temp.newFile("sample.docx");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(document))) {
            zip.putNextEntry(new ZipEntry("word/media/image12.png"));
            zip.write("answer diagram".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("text".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertEquals(Arrays.asList("docx:word/media/image12.png"), DocxMediaStore.list(document));
        assertEquals("answer diagram", new String(DocxMediaStore.read(document,
                "docx:word/media/image12.png"), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> DocxMediaStore.read(document,
                "docx:word/media/../../secret.png"));
    }
}
