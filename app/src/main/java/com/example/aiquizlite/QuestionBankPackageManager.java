package com.example.aiquizlite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class QuestionBankPackageManager {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 500;
    private static final long MAX_ENTRY_BYTES = 25L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 100L * 1024L * 1024L;
    private static final String MANIFEST_NAME = "question-bank.json";
    private static final String EXPORT_DIR = "question_bank_exports";
    private static final String IMPORT_TEMP_DIR = "question_bank_imports";

    private QuestionBankPackageManager() {
    }

    public static ExportedPackage exportToCache(
            Context context,
            QuestionRepository.QuestionBank bank
    ) throws Exception {
        List<Question> questions = QuestionRepository.load(context, bank.getId());
        JSONArray jsonQuestions = QuestionRepository.questionsToJson(questions);
        Map<String, String> archiveNames = new HashMap<>();
        int imageIndex = 1;
        for (int i = 0; i < jsonQuestions.length(); i++) {
            JSONObject item = jsonQuestions.getJSONObject(i);
            String imageReference = item.isNull("image") ? null : item.optString("image", null);
            if (imageReference == null || imageReference.trim().isEmpty()) {
                item.put("image", JSONObject.NULL);
                continue;
            }
            String entryName = archiveNames.get(imageReference);
            if (entryName == null) {
                entryName = "images/" + QuestionBankPackageRules.exportImageName(
                        imageIndex++,
                        imageReference
                );
                archiveNames.put(imageReference, entryName);
            }
            item.put("image", entryName);
        }

        JSONObject manifest = new JSONObject();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("name", bank.getDisplayName());
        manifest.put("questions", jsonQuestions);

        File directory = new File(context.getCacheDir(), EXPORT_DIR);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建导出目录");
        }
        String displayFileName = safeExportFileName(bank.getDisplayName()) + ".zip";
        File output = new File(
                directory,
                safeExportFileName(bank.getDisplayName()) + "_" + System.currentTimeMillis() + ".zip"
        );
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            zip.putNextEntry(new ZipEntry(MANIFEST_NAME));
            zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            for (Map.Entry<String, String> entry : archiveNames.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getValue()));
                try (InputStream image = QuestionImageStore.open(
                        context,
                        bank.getId(),
                        entry.getKey()
                )) {
                    copy(image, zip);
                }
                zip.closeEntry();
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        }
        return new ExportedPackage(output, displayFileName);
    }

    public static QuestionRepository.QuestionBank importPackage(
            Context context,
            InputStream source
    ) throws Exception {
        File tempRoot = new File(context.getCacheDir(), IMPORT_TEMP_DIR);
        if (!tempRoot.exists() && !tempRoot.mkdirs()) {
            throw new IllegalStateException("无法创建导入临时目录");
        }
        File temp = new File(tempRoot, UUID.randomUUID().toString());
        if (!temp.mkdirs()) {
            throw new IllegalStateException("无法创建导入临时目录");
        }
        try {
            extractValidated(source, temp);
            File manifestFile = new File(temp, MANIFEST_NAME);
            if (!manifestFile.isFile()) {
                throw new IllegalArgumentException("ZIP缺少question-bank.json");
            }
            JSONObject manifest = new JSONObject(readText(manifestFile, 4 * 1024 * 1024));
            if (manifest.optInt("formatVersion", -1) != FORMAT_VERSION) {
                throw new IllegalArgumentException("不支持的题库包版本");
            }
            JSONArray packagedQuestions = manifest.optJSONArray("questions");
            if (packagedQuestions == null || packagedQuestions.length() == 0) {
                throw new IllegalArgumentException("题库包没有题目");
            }
            String displayName = uniqueDisplayName(
                    context,
                    manifest.optString("name", "导入题库")
            );

            JSONArray placeholderQuestions = new JSONArray(packagedQuestions.toString());
            validateImageEntries(temp, placeholderQuestions, true);
            QuestionRepository.QuestionBank created = QuestionRepository.importQuestionBank(
                    context,
                    displayName,
                    placeholderQuestions
            );
            try {
                JSONArray importedQuestions = new JSONArray(packagedQuestions.toString());
                Map<String, String> importedImages = new HashMap<>();
                for (int i = 0; i < importedQuestions.length(); i++) {
                    JSONObject item = importedQuestions.getJSONObject(i);
                    String archiveReference = item.isNull("image")
                            ? null
                            : item.optString("image", null);
                    if (archiveReference == null || archiveReference.trim().isEmpty()) {
                        item.put("image", JSONObject.NULL);
                        continue;
                    }
                    String normalized = QuestionBankPackageRules.validateEntryName(archiveReference);
                    String localReference = importedImages.get(normalized);
                    if (localReference == null) {
                        File imageFile = safeExtractedFile(temp, normalized);
                        try (InputStream image = new FileInputStream(imageFile)) {
                            localReference = QuestionImageStore.importFromStream(
                                    context,
                                    created.getId(),
                                    image
                            );
                        }
                        importedImages.put(normalized, localReference);
                    }
                    item.put("image", localReference);
                }
                List<Question> parsed = QuestionRepository.parsePackagedQuestions(
                        importedQuestions,
                        displayName
                );
                QuestionRepository.saveImportedQuestionBank(context, created.getId(), parsed);
                return QuestionRepository.findQuestionBank(context, created.getId());
            } catch (Exception e) {
                QuestionRepository.deleteQuestionBank(context, created.getId());
                throw e;
            }
        } finally {
            deleteTree(temp);
        }
    }

    private static void extractValidated(InputStream source, File destination) throws Exception {
        int entries = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("ZIP文件数量过多");
                }
                String name = QuestionBankPackageRules.validateEntryName(entry.getName());
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("ZIP包含重复文件：" + name);
                }
                File target = safeExtractedFile(destination, name);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("无法创建导入目录");
                }
                long entryTotal = 0;
                try (FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        entryTotal += count;
                        total += count;
                        if (entryTotal > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new IllegalArgumentException("ZIP内容过大");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void validateImageEntries(
            File temp,
            JSONArray questions,
            boolean clearReferences
    ) throws Exception {
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.getJSONObject(i);
            String reference = item.isNull("image") ? null : item.optString("image", null);
            if (reference == null || reference.trim().isEmpty()) {
                item.put("image", JSONObject.NULL);
                continue;
            }
            String normalized = QuestionBankPackageRules.validateEntryName(reference);
            if (!normalized.startsWith("images/") || !safeExtractedFile(temp, normalized).isFile()) {
                throw new IllegalArgumentException("题目引用的图片不存在：" + reference);
            }
            if (clearReferences) {
                item.put("image", JSONObject.NULL);
            }
        }
    }

    private static File safeExtractedFile(File root, String name) throws Exception {
        File file = new File(root, name);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(rootPath)) {
            throw new IllegalArgumentException("ZIP路径不安全");
        }
        return file;
    }

    private static String uniqueDisplayName(Context context, String requested) {
        List<String> names = new ArrayList<>();
        for (QuestionRepository.QuestionBank bank : QuestionRepository.getQuestionBanks(context)) {
            names.add(bank.getDisplayName());
        }
        return QuestionBankPackageRules.uniqueDisplayName(requested, names);
    }

    private static String readText(File file, int maxBytes) throws Exception {
        try (InputStream inputStream = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("题库清单过大");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static String safeExportFileName(String displayName) {
        String safe = displayName == null ? "" : displayName.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ");
        return safe.isEmpty() ? "题库" : safe;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static final class ExportedPackage {
        private final File file;
        private final String displayFileName;

        ExportedPackage(File file, String displayFileName) {
            this.file = file;
            this.displayFileName = displayFileName;
        }

        public File getFile() {
            return file;
        }

        public String getDisplayFileName() {
            return displayFileName;
        }
    }
}
