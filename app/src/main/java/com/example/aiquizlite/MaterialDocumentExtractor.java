package com.example.aiquizlite;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class MaterialDocumentExtractor {
    public static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;

    public DocumentData extract(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) {
            throw new IllegalArgumentException("请选择资料文件");
        }
        ContentResolver resolver = context.getContentResolver();
        Metadata metadata = readMetadata(resolver, uri);
        if (metadata.size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("资料不能超过 20 MB");
        }
        String kind = resolveKind(metadata.name, resolver.getType(uri));
        byte[] bytes;
        try (InputStream input = requireInput(resolver, uri)) {
            bytes = readAll(input, MAX_FILE_BYTES);
        }
        String text;
        int pageCount = 0;
        switch (kind) {
            case "TXT":
                text = decodeText(bytes);
                break;
            case "DOCX":
                try (InputStream input = new ByteArrayInputStream(bytes)) {
                    text = extractDocx(input);
                }
                break;
            case "PDF":
                PDFBoxResourceLoader.init(context.getApplicationContext());
                try (PDDocument document = PDDocument.load(bytes)) {
                    pageCount = document.getNumberOfPages();
                    text = new PDFTextStripper().getText(document);
                }
                break;
            default:
                throw new IllegalArgumentException("仅支持 TXT、文字版 PDF 和 DOCX");
        }
        text = MaterialTextRules.normalizeText(text);
        if (text.length() < 10) {
            if ("PDF".equals(kind)) {
                throw new IllegalArgumentException("没有读取到可用文字；扫描版 PDF 暂不支持，请先进行 OCR");
            }
            throw new IllegalArgumentException("资料中没有读取到足够的文字");
        }
        return new DocumentData(
                metadata.name,
                kind,
                metadata.size >= 0L ? metadata.size : bytes.length,
                pageCount,
                text
        );
    }

    static String extractDocx(InputStream source) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) {
                    continue;
                }
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(zip, StandardCharsets.UTF_8.name());
                StringBuilder result = new StringBuilder();
                boolean insideText = false;
                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && "t".equals(parser.getName())) {
                        insideText = true;
                    } else if (event == XmlPullParser.TEXT && insideText) {
                        result.append(parser.getText());
                    } else if (event == XmlPullParser.END_TAG) {
                        String name = parser.getName();
                        if ("t".equals(name)) {
                            insideText = false;
                        } else if ("tab".equals(name)) {
                            result.append('\t');
                        } else if ("br".equals(name) || "cr".equals(name)) {
                            result.append('\n');
                        } else if ("p".equals(name)) {
                            result.append('\n');
                        }
                    }
                    event = parser.next();
                }
                return result.toString();
            }
        }
        throw new IllegalArgumentException("DOCX 中缺少正文内容");
    }

    private static String decodeText(byte[] bytes) {
        int offset = bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString();
        } catch (CharacterCodingException ignored) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static byte[] readAll(InputStream input, long maximumBytes) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new IllegalArgumentException("资料不能超过 20 MB");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static InputStream requireInput(ContentResolver resolver, Uri uri) throws Exception {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            throw new IllegalArgumentException("无法读取所选资料");
        }
        return input;
    }

    private static Metadata readMetadata(ContentResolver resolver, Uri uri) {
        String name = "学习资料";
        long size = -1L;
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) {
                    String candidate = cursor.getString(nameIndex);
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        name = candidate.trim();
                    }
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
        }
        return new Metadata(name, size);
    }

    private static String resolveKind(String name, String mimeType) {
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf") || "application/pdf".equals(mime)) {
            return "PDF";
        }
        if (lowerName.endsWith(".docx")
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
            return "DOCX";
        }
        if (lowerName.endsWith(".txt") || mime.startsWith("text/")) {
            return "TXT";
        }
        return "";
    }

    private static final class Metadata {
        final String name;
        final long size;

        Metadata(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    public static final class DocumentData {
        private final String displayName;
        private final String kind;
        private final long fileSize;
        private final int pageCount;
        private final String text;

        DocumentData(String displayName, String kind, long fileSize, int pageCount, String text) {
            this.displayName = displayName;
            this.kind = kind;
            this.fileSize = fileSize;
            this.pageCount = pageCount;
            this.text = text;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getKind() {
            return kind;
        }

        public long getFileSize() {
            return fileSize;
        }

        public int getPageCount() {
            return pageCount;
        }

        public String getText() {
            return text;
        }

        public int getCharacterCount() {
            return text.length();
        }

        public String describe() {
            if (pageCount > 0) {
                return pageCount + " 页 · " + getCharacterCount() + " 字";
            }
            return getCharacterCount() + " 字";
        }
    }
}
