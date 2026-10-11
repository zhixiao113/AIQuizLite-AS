package com.example.aiquizlite;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** App-private 24-hour DOCX source used only while a material-import draft is open. */
final class DocxMediaStore {
    static final String PREFIX = "docx:";
    private static final String FILE_NAME = "material_draft_source.docx";
    private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;

    private DocxMediaStore() { }

    static void stage(Context context, byte[] document) throws Exception {
        File target = file(context);
        File temporary = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(document);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("无法替换旧的 DOCX 校对草稿");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("无法保存 DOCX 校对图片");
        }
    }

    static List<String> list(Context context) throws Exception {
        return list(file(context));
    }

    static List<String> list(File source) throws Exception {
        List<String> result = new ArrayList<>();
        if (!source.isFile()) return result;
        try (InputStream input = new FileInputStream(source);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (validEntry(entry.getName())) result.add(PREFIX + entry.getName());
            }
        }
        return result;
    }

    static byte[] read(Context context, String reference) throws Exception {
        return read(file(context), reference);
    }

    static byte[] read(File source, String reference) throws Exception {
        if (!isReference(reference)) throw new IllegalArgumentException("不是 DOCX 图片引用");
        String target = reference.substring(PREFIX.length());
        if (!validEntry(target)) throw new IllegalArgumentException("DOCX 图片路径不安全");
        if (!source.isFile()) throw new IllegalArgumentException("原 DOCX 校对草稿已失效，请重新选择文件");
        try (InputStream input = new FileInputStream(source);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!target.equals(entry.getName())) continue;
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        if (output.size() + count > MAX_IMAGE_BYTES) {
                            throw new IllegalArgumentException("DOCX 图片过大");
                        }
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            }
        }
        throw new IllegalArgumentException("DOCX 中找不到图片：" + target);
    }

    static String importToBank(Context context, String bankId, String reference) throws Exception {
        return QuestionImageStore.importFromStream(context, bankId,
                new ByteArrayInputStream(read(context, reference)));
    }

    static boolean isReference(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    static void clear(Context context) {
        File source = file(context);
        if (source.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
        File temporary = new File(source.getParentFile(), FILE_NAME + ".tmp");
        if (temporary.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE_NAME);
    }

    private static boolean validEntry(String name) {
        return name != null && name.matches("word/media/[A-Za-z0-9._-]+\\.(?i:png|jpg|jpeg|webp|gif)");
    }
}
