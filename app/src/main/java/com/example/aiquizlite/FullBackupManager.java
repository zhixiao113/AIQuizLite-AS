package com.example.aiquizlite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class FullBackupManager {
    private static final String MANIFEST = "manifest.json";
    private static final String DATA_ROOT = "data/";
    private static final String FILE_ROOT = "files/";
    private static final String QUESTION_BANK_DIR = "question_banks";
    private static final String QUESTION_IMAGE_DIR = "question_bank_images";
    private static final String PREF_QUESTION_REPOSITORY = "question_repository";
    private static final String PREF_PROGRESS = "quiz_progress";
    private static final String PREF_MOCK = "mock_exam_attempts";
    private static final String PREF_GENERAL = "general_settings_v1";
    private static final String[] BACKED_UP_PREFERENCES = {
            PREF_QUESTION_REPOSITORY,
            PREF_PROGRESS,
            PREF_MOCK,
            PREF_GENERAL
    };

    private FullBackupManager() {
    }

    public static synchronized CreatedBackup create(Context context) throws Exception {
        long createdAt = System.currentTimeMillis();
        BackupSummary summary = currentSummary(context, createdAt);
        File root = new File(context.getCacheDir(), "full_backups");
        ensureDirectory(root);
        File target = new File(root, FullBackupRules.fileName(createdAt));
        try {
            writeSnapshot(context, target, summary);
            return new CreatedBackup(target, summary);
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw error;
        }
    }

    public static PreparedBackup prepare(Context context, InputStream source) throws Exception {
        File root = new File(context.getCacheDir(), "prepared_backups");
        ensureDirectory(root);
        File target = new File(root, UUID.randomUUID() + ".zip");
        try {
            if (source == null) throw new IllegalArgumentException("无法读取所选备份文件");
            try (InputStream input = source; OutputStream output = new FileOutputStream(target)) {
                copyLimited(input, output, FullBackupRules.MAX_UNCOMPRESSED_BYTES);
            }
            File extracted = extractValidated(context, target);
            try {
                return new PreparedBackup(target, readSummary(extracted));
            } finally {
                deleteRecursively(extracted);
            }
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw error;
        }
    }

    public static synchronized void restore(Context context, PreparedBackup prepared) throws Exception {
        File incoming = extractValidated(context, prepared.file);
        BackupSummary verified = readSummary(incoming);
        if (verified.formatVersion != FullBackupRules.FORMAT_VERSION) {
            deleteRecursively(incoming);
            throw new IllegalArgumentException("不支持的备份版本");
        }

        CreatedBackup rollback = create(context);
        File rollbackExtracted = null;
        try {
            rollbackExtracted = extractValidated(context, rollback.file);
            applyExtracted(context, incoming);
        } catch (Exception restoreError) {
            try {
                if (rollbackExtracted != null) {
                    applyExtracted(context, rollbackExtracted);
                }
            } catch (Exception rollbackError) {
                restoreError.addSuppressed(rollbackError);
            }
            throw restoreError;
        } finally {
            deleteRecursively(incoming);
            deleteRecursively(rollbackExtracted);
            //noinspection ResultOfMethodCallIgnored
            rollback.file.delete();
        }
    }

    public static BackupSummary currentSummary(Context context, long createdAt) {
        int importedBanks = 0;
        int questions = 0;
        int images = 0;
        for (QuestionRepository.QuestionBank bank : QuestionRepository.getQuestionBanks(context)) {
            if (!bank.isImported()) {
                continue;
            }
            importedBanks++;
            questions += bank.getQuestionCount();
            try {
                images += QuestionImageStore.countReferencedImages(
                        QuestionRepository.load(context, bank.getId())
                );
            } catch (Exception ignored) {
            }
        }
        return new BackupSummary(
                FullBackupRules.FORMAT_VERSION,
                createdAt,
                importedBanks,
                questions,
                images,
                preferences(context, PREF_PROGRESS).getAll().size(),
                preferences(context, PREF_MOCK).getAll().size(),
                preferences(context, PREF_GENERAL).getAll().size()
        );
    }

    @SuppressLint("ApplySharedPref")
    public static void clearLearningRecords(Context context) {
        preferences(context, PREF_PROGRESS).edit().clear().commit();
        preferences(context, PREF_MOCK).edit().clear().commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void clearOrdinarySettings(Context context) {
        preferences(context, PREF_GENERAL).edit().clear().commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void clearAllNonSensitiveData(Context context) {
        clearLearningRecords(context);
        clearOrdinarySettings(context);
        QuestionRepository.clearImportedQuestionBanks(context);
        new AiExplanationCache(context).clear();
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private static void writeSnapshot(Context context, File target, BackupSummary summary) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            writeText(zip, MANIFEST, summary.toJson().toString(2));
            for (String prefName : BACKED_UP_PREFERENCES) {
                writeText(zip, DATA_ROOT + prefName + ".json",
                        encodePreferences(preferences(context, prefName)).toString());
            }
            writeDirectory(zip, new File(context.getFilesDir(), QUESTION_BANK_DIR),
                    FILE_ROOT + QUESTION_BANK_DIR + "/");
            writeDirectory(zip, new File(context.getFilesDir(), QUESTION_IMAGE_DIR),
                    FILE_ROOT + QUESTION_IMAGE_DIR + "/");
        }
    }

    private static File extractValidated(Context context, File archive) throws Exception {
        File root = new File(context.getCacheDir(), "backup_extract_" + UUID.randomUUID());
        ensureDirectory(root);
        String rootPath = root.getCanonicalPath() + File.separator;
        int entries = 0;
        long totalBytes = 0L;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > FullBackupRules.MAX_ENTRY_COUNT
                        || !FullBackupRules.isSafeEntryName(entry.getName())) {
                    throw new IllegalArgumentException("备份文件结构不安全");
                }
                File target = new File(root, entry.getName());
                if (!target.getCanonicalPath().startsWith(rootPath)) {
                    throw new IllegalArgumentException("备份文件结构不安全");
                }
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                    continue;
                }
                ensureDirectory(target.getParentFile());
                try (OutputStream output = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > FullBackupRules.MAX_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException("备份内容过大");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        } catch (Exception error) {
            deleteRecursively(root);
            throw error;
        }
        readSummary(root);
        for (String prefName : BACKED_UP_PREFERENCES) {
            decodePreferences(readJson(new File(root, DATA_ROOT + prefName + ".json")));
        }
        return root;
    }

    private static BackupSummary readSummary(File extracted) throws Exception {
        File manifest = new File(extracted, MANIFEST);
        if (!manifest.isFile()) {
            throw new IllegalArgumentException("备份缺少清单文件");
        }
        BackupSummary summary = BackupSummary.fromJson(readJson(manifest));
        if (summary.formatVersion != FullBackupRules.FORMAT_VERSION) {
            throw new IllegalArgumentException("不支持的备份版本");
        }
        return summary;
    }

    private static void applyExtracted(Context context, File extracted) throws Exception {
        replaceDirectory(
                new File(context.getFilesDir(), QUESTION_BANK_DIR),
                new File(extracted, FILE_ROOT + QUESTION_BANK_DIR)
        );
        replaceDirectory(
                new File(context.getFilesDir(), QUESTION_IMAGE_DIR),
                new File(extracted, FILE_ROOT + QUESTION_IMAGE_DIR)
        );

        for (String prefName : BACKED_UP_PREFERENCES) {
            JSONObject snapshot = readJson(new File(extracted, DATA_ROOT + prefName + ".json"));
            if (PREF_QUESTION_REPOSITORY.equals(prefName)) {
                rewriteImportedBankPaths(context, snapshot);
            }
            applyPreferences(preferences(context, prefName), decodePreferences(snapshot));
        }
        QuestionRepository.resetCache();
    }

    private static void rewriteImportedBankPaths(Context context, JSONObject snapshot) throws Exception {
        JSONObject values = snapshot.getJSONObject("values");
        JSONObject imported = values.optJSONObject("imported_banks");
        if (imported == null || !"string".equals(imported.optString("type"))) {
            return;
        }
        JSONArray banks = new JSONArray(imported.optString("value", "[]"));
        File targetDir = new File(context.getFilesDir(), QUESTION_BANK_DIR);
        for (int index = 0; index < banks.length(); index++) {
            JSONObject bank = banks.getJSONObject(index);
            String originalPath = bank.optString("path", "");
            String fileName = FullBackupRules.safeFileName(new File(originalPath).getName());
            File target = new File(targetDir, fileName);
            if (!target.isFile()) {
                throw new IllegalArgumentException("备份缺少题库文件：" + fileName);
            }
            bank.put("path", target.getAbsolutePath());
        }
        imported.put("value", banks.toString());
    }

    private static JSONObject encodePreferences(SharedPreferences preferences) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            JSONObject encoded = new JSONObject();
            if (value instanceof String) {
                encoded.put("type", "string").put("value", value);
            } else if (value instanceof Integer) {
                encoded.put("type", "int").put("value", value);
            } else if (value instanceof Long) {
                encoded.put("type", "long").put("value", value);
            } else if (value instanceof Float) {
                encoded.put("type", "float").put("value", ((Float) value).doubleValue());
            } else if (value instanceof Boolean) {
                encoded.put("type", "boolean").put("value", value);
            } else if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    if (!(item instanceof String)) {
                        throw new IllegalArgumentException("设置中包含不支持的数据类型");
                    }
                    array.put(item);
                }
                encoded.put("type", "stringSet").put("value", array);
            } else {
                throw new IllegalArgumentException("设置中包含不支持的数据类型");
            }
            values.put(entry.getKey(), encoded);
        }
        root.put("values", values);
        return root;
    }

    private static List<PreferenceValue> decodePreferences(JSONObject root) throws Exception {
        JSONObject values = root.getJSONObject("values");
        List<PreferenceValue> decoded = new ArrayList<>();
        for (String key : keys(values)) {
            JSONObject item = values.getJSONObject(key);
            String type = item.getString("type");
            Object value;
            switch (type) {
                case "string": value = item.getString("value"); break;
                case "int": value = item.getInt("value"); break;
                case "long": value = item.getLong("value"); break;
                case "float": value = (float) item.getDouble("value"); break;
                case "boolean": value = item.getBoolean("value"); break;
                case "stringSet":
                    JSONArray array = item.getJSONArray("value");
                    Set<String> set = new HashSet<>();
                    for (int index = 0; index < array.length(); index++) {
                        set.add(array.getString(index));
                    }
                    value = set;
                    break;
                default: throw new IllegalArgumentException("备份包含不支持的设置类型");
            }
            decoded.add(new PreferenceValue(key, value));
        }
        return decoded;
    }

    @SuppressWarnings("unchecked")
    private static void applyPreferences(
            SharedPreferences preferences,
            List<PreferenceValue> values
    ) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (PreferenceValue item : values) {
            Object value = item.value;
            if (value instanceof String) editor.putString(item.key, (String) value);
            else if (value instanceof Integer) editor.putInt(item.key, (Integer) value);
            else if (value instanceof Long) editor.putLong(item.key, (Long) value);
            else if (value instanceof Float) editor.putFloat(item.key, (Float) value);
            else if (value instanceof Boolean) editor.putBoolean(item.key, (Boolean) value);
            else if (value instanceof Set) editor.putStringSet(item.key, (Set<String>) value);
        }
        if (!editor.commit()) {
            throw new IllegalStateException("无法写入恢复数据");
        }
    }

    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        object.keys().forEachRemaining(keys::add);
        return keys;
    }

    private static void writeText(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeDirectory(ZipOutputStream zip, File directory, String prefix) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String safeName = FullBackupRules.safeFileName(file.getName());
            if (file.isDirectory()) {
                writeDirectory(zip, file, prefix + safeName + "/");
            } else if (file.isFile()) {
                zip.putNextEntry(new ZipEntry(prefix + safeName));
                try (InputStream input = new FileInputStream(file)) {
                    copy(input, zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static JSONObject readJson(File file) throws Exception {
        if (!file.isFile() || file.length() > 16L * 1024L * 1024L) {
            throw new IllegalArgumentException("备份缺少必要数据或内容过大");
        }
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copy(input, output);
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private static void replaceDirectory(File target, File source) throws Exception {
        deleteRecursively(target);
        if (!source.exists()) {
            return;
        }
        copyDirectory(source, target);
    }

    private static void copyDirectory(File source, File target) throws Exception {
        ensureDirectory(target);
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            File child = new File(target, FullBackupRules.safeFileName(file.getName()));
            if (file.isDirectory()) {
                copyDirectory(file, child);
            } else {
                try (InputStream input = new FileInputStream(file);
                     OutputStream output = new FileOutputStream(child)) {
                    copy(input, output);
                }
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void copyLimited(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("备份文件过大");
            output.write(buffer, 0, read);
        }
    }

    private static void ensureDirectory(File directory) {
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建临时目录");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static SharedPreferences preferences(Context context, String name) {
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static final class PreferenceValue {
        final String key;
        final Object value;

        PreferenceValue(String key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    public static final class CreatedBackup {
        private final File file;
        private final BackupSummary summary;

        CreatedBackup(File file, BackupSummary summary) {
            this.file = file;
            this.summary = summary;
        }

        public File getFile() { return file; }
        public BackupSummary getSummary() { return summary; }
    }

    public static final class PreparedBackup {
        private final File file;
        private final BackupSummary summary;

        PreparedBackup(File file, BackupSummary summary) {
            this.file = file;
            this.summary = summary;
        }

        public File getFile() { return file; }
        public BackupSummary getSummary() { return summary; }
        public void discard() { //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public static final class BackupSummary {
        private final int formatVersion;
        private final long createdAt;
        private final int importedBankCount;
        private final int questionCount;
        private final int imageCount;
        private final int learningRecordCount;
        private final int mockAttemptCount;
        private final int settingCount;

        BackupSummary(int formatVersion, long createdAt, int importedBankCount, int questionCount,
                      int imageCount, int learningRecordCount, int mockAttemptCount, int settingCount) {
            this.formatVersion = formatVersion;
            this.createdAt = createdAt;
            this.importedBankCount = importedBankCount;
            this.questionCount = questionCount;
            this.imageCount = imageCount;
            this.learningRecordCount = learningRecordCount;
            this.mockAttemptCount = mockAttemptCount;
            this.settingCount = settingCount;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("formatVersion", formatVersion)
                    .put("createdAt", createdAt)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("importedBankCount", importedBankCount)
                    .put("questionCount", questionCount)
                    .put("imageCount", imageCount)
                    .put("learningRecordCount", learningRecordCount)
                    .put("mockAttemptCount", mockAttemptCount)
                    .put("settingCount", settingCount)
                    .put("excludes", new JSONArray()
                            .put("apiCredentials")
                            .put("aiProviderConfig")
                            .put("downloadedQuestionBankArchives")
                            .put("aiExplanationCache"));
        }

        static BackupSummary fromJson(JSONObject json) {
            return new BackupSummary(
                    json.optInt("formatVersion", -1),
                    json.optLong("createdAt", 0L),
                    json.optInt("importedBankCount", 0),
                    json.optInt("questionCount", 0),
                    json.optInt("imageCount", 0),
                    json.optInt("learningRecordCount", 0),
                    json.optInt("mockAttemptCount", 0),
                    json.optInt("settingCount", 0)
            );
        }

        public long getCreatedAt() { return createdAt; }
        public int getImportedBankCount() { return importedBankCount; }
        public int getQuestionCount() { return questionCount; }
        public int getImageCount() { return imageCount; }
        public int getLearningRecordCount() { return learningRecordCount; }
        public int getMockAttemptCount() { return mockAttemptCount; }
        public int getSettingCount() { return settingCount; }
    }
}
