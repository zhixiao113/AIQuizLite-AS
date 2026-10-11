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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class QuestionBankPackageManager {
    private static final int FORMAT_VERSION = 2;
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
        boolean needsVersionTwo = false;
        for (int i = 0; i < jsonQuestions.length(); i++) {
            JSONObject item = jsonQuestions.getJSONObject(i);
            needsVersionTwo |= "matching".equals(item.optString("type", ""))
                    || (item.optJSONArray("explanationImages") != null
                            && item.optJSONArray("explanationImages").length() > 0);
            for (String field : new String[]{"image", "explanationImages"}) {
                if ("image".equals(field)) {
                    String reference = optionalImageReference(item);
                    if (reference.isEmpty()) {
                        item.put(field, JSONObject.NULL);
                    } else {
                        String entry = archiveNames.get(reference);
                        if (entry == null) {
                            entry = "images/" + QuestionBankPackageRules.exportImageName(
                                    imageIndex++, reference);
                            archiveNames.put(reference, entry);
                        }
                        item.put(field, entry);
                    }
                } else {
                    JSONArray images = item.optJSONArray(field);
                    JSONArray packagedImages = new JSONArray();
                    if (images != null) for (int j = 0; j < images.length(); j++) {
                        String reference = images.getString(j);
                        String entry = archiveNames.get(reference);
                        if (entry == null) {
                            entry = "images/" + QuestionBankPackageRules.exportImageName(
                                    imageIndex++, reference);
                            archiveNames.put(reference, entry);
                        }
                        packagedImages.put(entry);
                    }
                    item.put(field, packagedImages);
                }
            }
        }

        JSONObject manifest = new JSONObject();
        manifest.put("formatVersion", needsVersionTwo ? FORMAT_VERSION : 1);
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
            PackagedBank packaged = readPackage(source, temp);
            JSONArray packagedQuestions = packaged.questions;
            String displayName = uniqueDisplayName(context, packaged.displayName);

            JSONArray placeholderQuestions = new JSONArray(packagedQuestions.toString());
            for (int i = 0; i < placeholderQuestions.length(); i++) {
                placeholderQuestions.getJSONObject(i).put("image", JSONObject.NULL);
                placeholderQuestions.getJSONObject(i).put("explanationImages", new JSONArray());
            }
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
                    String image = optionalImageReference(item);
                    item.put("image", image.isEmpty() ? JSONObject.NULL
                            : importImage(context, created.getId(), temp, image, importedImages));
                    JSONArray explanationImages = item.optJSONArray("explanationImages");
                    JSONArray localExplanations = new JSONArray();
                    if (explanationImages != null) for (int j = 0; j < explanationImages.length(); j++) {
                        localExplanations.put(importImage(context, created.getId(), temp,
                                explanationImages.getString(j), importedImages));
                    }
                    item.put("explanationImages", localExplanations);
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

    // Kept independent of Android storage so real ZIP inputs can be checked in unit tests.
    static PackagedBank readPackage(InputStream source, File destination) throws Exception {
        Set<String> files = extractValidated(source, destination);
        String jsonName = null;
        for (String name : files) {
            if (!QuestionBankPackageRules.isImageEntry(name)
                    && name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                if (jsonName != null) {
                    throw new IllegalArgumentException("ZIP包含多个题库 JSON，请只保留一个");
                }
                jsonName = name;
            }
        }
        if (jsonName == null) {
            throw new IllegalArgumentException("ZIP缺少题库 JSON，请放入一个 JSON 文件和 image 文件夹");
        }
        String raw = readText(safeExtractedFile(destination, jsonName), 4 * 1024 * 1024).trim();
        if (raw.startsWith("\uFEFF")) {
            raw = raw.substring(1).trim();
        }
        if (raw.startsWith("{")) {
            JSONObject manifest = new JSONObject(raw);
            int version = manifest.optInt("formatVersion", 1);
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IllegalArgumentException("不支持的题库包版本");
            }
            if (version == 1 && manifest.optJSONArray("questions") != null) {
                JSONArray versionQuestions = manifest.getJSONArray("questions");
                for (int i = 0; i < versionQuestions.length(); i++) {
                    if ("matching".equals(versionQuestions.getJSONObject(i).optString("type", ""))) {
                        throw new IllegalArgumentException("连线题需要新版题库包格式");
                    }
                }
            }
        }
        JSONArray questions = QuestionRepository.normalizeImportedJson(raw);
        if (questions.length() == 0) {
            throw new IllegalArgumentException("题库包没有题目");
        }
        String fileName = new File(jsonName).getName();
        String fallback = fileName.substring(0, fileName.length() - 5);
        String displayName = QuestionRepository.resolveDisplayNameFromRawJson(raw, fallback);
        String base = jsonName.contains("/") ? jsonName.substring(0, jsonName.lastIndexOf('/') + 1) : "";
        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.getJSONObject(i);
            String image = normalizeImageReference(item.opt("image"), i, base, files);
            item.put("image", image == null ? JSONObject.NULL : image);
            JSONArray explanationImages = item.optJSONArray("explanationImages");
            JSONArray normalizedExplanations = new JSONArray();
            if (explanationImages != null) for (int j = 0; j < explanationImages.length(); j++) {
                String reference = normalizeImageReference(explanationImages.get(j), i, base, files);
                if (reference == null) throw new IllegalArgumentException("解析图引用不能为空");
                normalizedExplanations.put(reference);
            }
            item.put("explanationImages", normalizedExplanations);
        }
        QuestionRepository.parsePackagedQuestions(questions, displayName);
        return new PackagedBank(displayName, questions);
    }

    static String optionalImageReference(JSONObject item) {
        // Android's JSONObject.optString can turn JSONObject.NULL into the literal "null".
        return item.isNull("image") ? "" : item.optString("image", "").trim();
    }

    private static String normalizeImageReference(Object reference, int questionIndex,
                                                   String base, Set<String> files) {
            if (reference == null || reference == JSONObject.NULL || "".equals(reference)) return null;
            if (!(reference instanceof String)) {
                throw new IllegalArgumentException("第 " + (questionIndex + 1) + " 题的图片引用必须是相对路径");
            }
            String imageName = ((String) reference).trim().replace('\\', '/');
            if (imageName.isEmpty()) return null;
            while (imageName.startsWith("./")) {
                imageName = imageName.substring(2);
            }
            String normalized;
            if (!imageName.contains("/")) {
                String singular = QuestionBankPackageRules.validateEntryName(base + "image/" + imageName);
                String plural = QuestionBankPackageRules.validateEntryName(base + "images/" + imageName);
                if (files.contains(singular) && files.contains(plural)) {
                    throw new IllegalArgumentException("图片名不唯一，请填写完整相对路径：" + reference);
                }
                normalized = files.contains(singular) ? singular : plural;
            } else {
                normalized = QuestionBankPackageRules.validateEntryName(base + imageName);
            }
            if (!QuestionBankPackageRules.isImageEntry(normalized) || !files.contains(normalized)) {
                throw new IllegalArgumentException("第 " + (questionIndex + 1) + " 题引用的图片不存在：" + reference);
            }
            return normalized;
    }

    private static String importImage(Context context, String bankId, File temp, String archiveReference,
                                      Map<String, String> importedImages) throws Exception {
        String normalized = QuestionBankPackageRules.validateEntryName(archiveReference);
        String localReference = importedImages.get(normalized);
        if (localReference == null) {
            File imageFile = safeExtractedFile(temp, normalized);
            try (InputStream image = new FileInputStream(imageFile)) {
                localReference = QuestionImageStore.importFromStream(context, bankId, image);
            }
            importedImages.put(normalized, localReference);
        }
        return localReference;
    }

    static final class PackagedBank {
        final String displayName;
        final JSONArray questions;

        PackagedBank(String displayName, JSONArray questions) {
            this.displayName = displayName;
            this.questions = questions;
        }
    }

    private static Set<String> extractValidated(InputStream source, File destination) throws Exception {
        int entries = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        Set<String> files = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("ZIP文件数量过多");
                }
                if (entry.isDirectory()) {
                    // A directory must not hide compressed payload outside the size limits.
                    if (zip.read() != -1) {
                        throw new IllegalArgumentException("ZIP目录条目不能包含文件内容");
                    }
                    zip.closeEntry();
                    continue;
                }
                String rawEntryName = entry.getName();
                if (QuestionBankPackageRules.isSystemJunkEntry(rawEntryName)) {
                    // macOS/Windows 压缩包常见垃圾文件，直接跳过不导入
                    zip.closeEntry();
                    continue;
                }
                String name = QuestionBankPackageRules.validateEntryName(rawEntryName);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("ZIP包含重复文件：" + name);
                }
                files.add(name);
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
        return files;
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
