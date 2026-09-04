package com.example.aiquizlite;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class DownloadArchiveStore {
    public static final String DISPLAY_FOLDER = "Download/AIQuizLite";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/AIQuizLite/";
    private static final String PREF_NAME = "download_archive_store";
    private static final String KEY_RECORDS = "records";

    private DownloadArchiveStore() {
    }

    public static String displayFolder(Context context) {
        return new GeneralSettingsStore(context).getDownloadDirectoryLabel();
    }

    public static synchronized ArchiveRecord saveExport(
            Context context,
            String sourceBankId,
            QuestionBankPackageManager.ExportedPackage exported
    ) throws Exception {
        List<ArchiveRecord> records = readRecords(context);
        records.removeIf(record -> !exists(context, record));
        GeneralSettingsStore settings = new GeneralSettingsStore(context);
        if (settings.hasCustomDownloadDirectory()) {
            try {
                ArchiveRecord customRecord = saveExportToTree(
                        context,
                        settings.getDownloadTreeUri(),
                        sourceBankId,
                        exported,
                        records
                );
                replaceSourceRecords(context, records, sourceBankId, customRecord);
                records.add(customRecord);
                writeRecords(context, records);
                return customRecord;
            } catch (Exception customError) {
                settings.clearDownloadTree();
            }
        }
        ArchiveRecord existing = null;
        for (ArchiveRecord record : records) {
            if (DownloadArchiveRules.shouldReplace(
                    sourceBankId,
                    record.getSourceBankId(),
                    exported.getDisplayFileName(),
                    record.getDisplayName()
            )) {
                existing = record;
                break;
            }
        }
        Set<String> names = new HashSet<>();
        for (ArchiveRecord record : records) {
            if (existing == null || !record.getLocation().equals(existing.getLocation())) {
                names.add(record.getDisplayName());
            }
        }
        String displayName = DownloadArchiveRules.uniqueFileName(
                exported.getDisplayFileName(),
                names
        );
        ArchiveRecord record;
        if (existing == null) {
            record = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? saveWithMediaStore(
                            context,
                            exported.getFile(),
                            displayName,
                            sourceBankId
                    )
                    : saveLegacy(exported.getFile(), displayName, sourceBankId);
        } else {
            record = existing.isContentUri()
                    ? replaceWithMediaStore(
                            context,
                            existing,
                            exported.getFile(),
                            displayName,
                            sourceBankId
                    )
                    : replaceLegacy(
                            existing,
                            exported.getFile(),
                            displayName,
                            sourceBankId
                    );
            String previousLocation = existing.getLocation();
            records.removeIf(item -> item.getLocation().equals(previousLocation));
        }
        replaceSourceRecords(context, records, sourceBankId, record);
        records.add(record);
        writeRecords(context, records);
        return record;
    }

    public static synchronized List<ArchiveRecord> list(Context context) {
        List<ArchiveRecord> records = readRecords(context);
        boolean changed = false;
        for (Iterator<ArchiveRecord> iterator = records.iterator(); iterator.hasNext(); ) {
            if (!exists(context, iterator.next())) {
                iterator.remove();
                changed = true;
            }
        }
        records.sort((left, right) -> Long.compare(right.getCreatedAt(), left.getCreatedAt()));
        if (changed) {
            writeRecords(context, records);
        }
        GeneralSettingsStore settings = new GeneralSettingsStore(context);
        if (settings.shouldDiscoverManualZip()) {
            List<ArchiveRecord> discovered = discoverCurrentFolder(context, settings);
            Set<String> knownLocations = new HashSet<>();
            for (ArchiveRecord record : records) {
                knownLocations.add(record.getLocation());
            }
            for (ArchiveRecord record : discovered) {
                if (knownLocations.add(record.getLocation())) {
                    records.add(record);
                }
            }
        }
        records.sort((left, right) -> Long.compare(right.getCreatedAt(), left.getCreatedAt()));
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public static synchronized void delete(Context context, ArchiveRecord record) throws Exception {
        if (record.isContentUri()) {
            Uri uri = Uri.parse(record.getLocation());
            try {
                if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                    context.getContentResolver().delete(uri, null, null);
                }
            } catch (Exception ignored) {
                context.getContentResolver().delete(uri, null, null);
            }
        } else {
            File file = new File(record.getLocation());
            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("无法删除下载文件");
            }
        }
        List<ArchiveRecord> records = readRecords(context);
        records.removeIf(item -> item.getLocation().equals(record.getLocation()));
        writeRecords(context, records);
    }

    public static InputStream open(Context context, ArchiveRecord record) throws Exception {
        InputStream inputStream = record.isContentUri()
                ? context.getContentResolver().openInputStream(Uri.parse(record.getLocation()))
                : new FileInputStream(new File(record.getLocation()));
        if (inputStream == null) {
            throw new IllegalStateException("下载文件不存在或无法读取");
        }
        return inputStream;
    }

    public static Uri shareUri(Context context, ArchiveRecord record) {
        if (record.isContentUri()) {
            return Uri.parse(record.getLocation());
        }
        return FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                new File(record.getLocation())
        );
    }

    private static ArchiveRecord saveExportToTree(
            Context context,
            Uri treeUri,
            String sourceBankId,
            QuestionBankPackageManager.ExportedPackage exported,
            List<ArchiveRecord> registeredRecords
    ) throws Exception {
        if (treeUri == null) {
            throw new IllegalStateException("未选择自定义下载文件夹");
        }
        List<ArchiveRecord> folderFiles = queryTreeZipFiles(context, treeUri);
        Set<String> folderLocations = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ArchiveRecord file : folderFiles) {
            folderLocations.add(file.getLocation());
            names.add(file.getDisplayName());
        }

        ArchiveRecord existing = null;
        for (ArchiveRecord record : registeredRecords) {
            if (folderLocations.contains(record.getLocation())
                    && DownloadArchiveRules.shouldReplace(
                    sourceBankId,
                    record.getSourceBankId(),
                    exported.getDisplayFileName(),
                    record.getDisplayName()
            )) {
                existing = record;
                break;
            }
        }
        if (existing != null) {
            names.remove(existing.getDisplayName());
        }
        String displayName = DownloadArchiveRules.uniqueFileName(
                exported.getDisplayFileName(),
                names
        );
        return existing == null
                ? createTreeDocument(context, treeUri, exported.getFile(), displayName, sourceBankId)
                : replaceTreeDocument(context, existing, exported.getFile(), displayName, sourceBankId);
    }

    private static ArchiveRecord createTreeDocument(
            Context context,
            Uri treeUri,
            File source,
            String displayName,
            String sourceBankId
    ) throws Exception {
        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId);
        Uri documentUri = DocumentsContract.createDocument(
                context.getContentResolver(),
                directoryUri,
                "application/zip",
                displayName
        );
        if (documentUri == null) {
            throw new IllegalStateException("无法在所选文件夹中创建 ZIP");
        }
        boolean complete = false;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(documentUri, "rwt")) {
            if (output == null) {
                throw new IllegalStateException("无法写入所选文件夹");
            }
            copy(input, output);
            output.flush();
            complete = true;
            return new ArchiveRecord(
                    documentUri.toString(),
                    true,
                    displayName,
                    System.currentTimeMillis(),
                    source.length(),
                    sourceBankId
            );
        } finally {
            if (!complete) {
                try {
                    DocumentsContract.deleteDocument(context.getContentResolver(), documentUri);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static ArchiveRecord replaceTreeDocument(
            Context context,
            ArchiveRecord existing,
            File source,
            String displayName,
            String sourceBankId
    ) throws Exception {
        Uri documentUri = Uri.parse(existing.getLocation());
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(documentUri, "rwt")) {
            if (output == null) {
                throw new IllegalStateException("无法覆盖所选文件夹中的题库包");
            }
            copy(input, output);
            output.flush();
        }
        if (!displayName.equals(existing.getDisplayName())) {
            Uri renamed = DocumentsContract.renameDocument(
                    context.getContentResolver(),
                    documentUri,
                    displayName
            );
            if (renamed != null) {
                documentUri = renamed;
            }
        }
        return new ArchiveRecord(
                documentUri.toString(),
                true,
                displayName,
                System.currentTimeMillis(),
                source.length(),
                sourceBankId
        );
    }

    private static void replaceSourceRecords(
            Context context,
            List<ArchiveRecord> records,
            String sourceBankId,
            ArchiveRecord replacement
    ) {
        if (sourceBankId == null || sourceBankId.isEmpty()) {
            records.removeIf(record -> record.getLocation().equals(replacement.getLocation()));
            return;
        }
        for (Iterator<ArchiveRecord> iterator = records.iterator(); iterator.hasNext(); ) {
            ArchiveRecord record = iterator.next();
            if (record.getLocation().equals(replacement.getLocation())) {
                iterator.remove();
            } else if (sourceBankId.equals(record.getSourceBankId())) {
                deleteFileQuietly(context, record);
                iterator.remove();
            }
        }
    }

    private static void deleteFileQuietly(Context context, ArchiveRecord record) {
        try {
            if (record.isContentUri()) {
                Uri uri = Uri.parse(record.getLocation());
                try {
                    DocumentsContract.deleteDocument(context.getContentResolver(), uri);
                } catch (Exception ignored) {
                    context.getContentResolver().delete(uri, null, null);
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                new File(record.getLocation()).delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static List<ArchiveRecord> discoverCurrentFolder(
            Context context,
            GeneralSettingsStore settings
    ) {
        try {
            if (settings.hasCustomDownloadDirectory()) {
                return queryTreeZipFiles(context, settings.getDownloadTreeUri());
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? queryMediaStoreZipFiles(context)
                    : queryLegacyZipFiles();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static List<ArchiveRecord> queryTreeZipFiles(Context context, Uri treeUri) throws Exception {
        List<ArchiveRecord> records = new ArrayList<>();
        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = context.getContentResolver().query(
                childrenUri,
                projection,
                null,
                null,
                null
        )) {
            if (cursor == null) {
                return records;
            }
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                long modified = cursor.isNull(2) ? 0L : cursor.getLong(2);
                long size = cursor.isNull(3) ? 0L : cursor.getLong(3);
                String mimeType = cursor.getString(4);
                if (!isZip(displayName, mimeType)) {
                    continue;
                }
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                records.add(new ArchiveRecord(
                        documentUri.toString(),
                        true,
                        displayName,
                        modified,
                        size,
                        ""
                ));
            }
        }
        return records;
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static List<ArchiveRecord> queryMediaStoreZipFiles(Context context) {
        List<ArchiveRecord> records = new ArrayList<>();
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
        };
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{RELATIVE_PATH},
                null
        )) {
            if (cursor == null) {
                return records;
            }
            while (cursor.moveToNext()) {
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(4);
                if (!isZip(displayName, mimeType)) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)
                );
                records.add(new ArchiveRecord(
                        uri.toString(),
                        true,
                        displayName,
                        cursor.getLong(2) * 1000L,
                        cursor.getLong(3),
                        ""
                ));
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    @SuppressWarnings("deprecation")
    private static List<ArchiveRecord> queryLegacyZipFiles() {
        List<ArchiveRecord> records = new ArrayList<>();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, "AIQuizLite");
        File[] files = directory.listFiles();
        if (files == null) {
            return records;
        }
        for (File file : files) {
            if (file.isFile() && isZip(file.getName(), null)) {
                records.add(new ArchiveRecord(
                        file.getAbsolutePath(),
                        false,
                        file.getName(),
                        file.lastModified(),
                        file.length(),
                        ""
                ));
            }
        }
        return records;
    }

    private static boolean isZip(String displayName, String mimeType) {
        return displayName != null
                && displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                && (mimeType == null
                || "application/zip".equalsIgnoreCase(mimeType)
                || "application/octet-stream".equalsIgnoreCase(mimeType));
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static ArchiveRecord saveWithMediaStore(
            Context context,
            File source,
            String displayName,
            String sourceBankId
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("无法在手机下载目录中创建文件");
        }
        boolean complete = false;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IllegalStateException("无法写入手机下载目录");
            }
            copy(input, output);
            output.flush();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            complete = true;
            return new ArchiveRecord(
                    uri.toString(),
                    true,
                    displayName,
                    System.currentTimeMillis(),
                    source.length(),
                    sourceBankId
            );
        } finally {
            if (!complete) {
                resolver.delete(uri, null, null);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static ArchiveRecord saveLegacy(
            File source,
            String displayName,
            String sourceBankId
    ) throws Exception {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, "AIQuizLite");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建 Download/AIQuizLite 文件夹");
        }
        Set<String> existingNames = new HashSet<>();
        File[] existingFiles = directory.listFiles();
        if (existingFiles != null) {
            for (File existingFile : existingFiles) {
                if (existingFile.isFile()) {
                    existingNames.add(existingFile.getName());
                }
            }
        }
        displayName = DownloadArchiveRules.uniqueFileName(displayName, existingNames);
        File target = new File(directory, displayName);
        boolean complete = false;
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
            output.flush();
            output.getFD().sync();
            complete = true;
            return new ArchiveRecord(
                    target.getAbsolutePath(),
                    false,
                    displayName,
                    System.currentTimeMillis(),
                    target.length(),
                    sourceBankId
            );
        } finally {
            if (!complete) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static ArchiveRecord replaceWithMediaStore(
            Context context,
            ArchiveRecord existing,
            File source,
            String displayName,
            String sourceBankId
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri uri = Uri.parse(existing.getLocation());
        ContentValues pending = new ContentValues();
        pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
        resolver.update(uri, pending, null, null);
        boolean complete = false;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "rwt")) {
            if (output == null) {
                throw new IllegalStateException("无法覆盖手机下载目录中的题库包");
            }
            copy(input, output);
            output.flush();
            long now = System.currentTimeMillis();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ready.put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000L);
            resolver.update(uri, ready, null, null);
            complete = true;
            return new ArchiveRecord(
                    uri.toString(),
                    true,
                    displayName,
                    now,
                    source.length(),
                    sourceBankId
            );
        } finally {
            if (!complete) {
                ContentValues visible = new ContentValues();
                visible.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, visible, null, null);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static ArchiveRecord replaceLegacy(
            ArchiveRecord existing,
            File source,
            String requestedDisplayName,
            String sourceBankId
    ) throws Exception {
        File existingFile = new File(existing.getLocation());
        File directory = existingFile.getParentFile();
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            throw new IllegalStateException("无法访问 Download/AIQuizLite 文件夹");
        }
        Set<String> diskNames = new HashSet<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && !file.equals(existingFile)) {
                    diskNames.add(file.getName());
                }
            }
        }
        String displayName = DownloadArchiveRules.uniqueFileName(requestedDisplayName, diskNames);
        File target = new File(directory, displayName);
        long marker = System.nanoTime();
        File temp = new File(directory, ".aiquizlite-" + marker + ".tmp");
        File backup = new File(directory, ".aiquizlite-" + marker + ".bak");
        boolean copied = false;
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temp)) {
            copy(input, output);
            output.flush();
            output.getFD().sync();
            copied = true;
        } finally {
            if (!copied) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }

        boolean backedUp = false;
        try {
            if (existingFile.exists()) {
                if (!existingFile.renameTo(backup)) {
                    throw new IllegalStateException("无法备份旧题库包");
                }
                backedUp = true;
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("无法替换旧题库包");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("无法写入新的题库包");
            }
            if (backedUp) {
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
            return new ArchiveRecord(
                    target.getAbsolutePath(),
                    false,
                    displayName,
                    System.currentTimeMillis(),
                    target.length(),
                    sourceBankId
            );
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (backedUp && !existingFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(existingFile);
            }
            throw error;
        }
    }

    private static boolean exists(Context context, ArchiveRecord record) {
        if (!record.isContentUri()) {
            return new File(record.getLocation()).isFile();
        }
        try (android.content.res.AssetFileDescriptor ignored = context.getContentResolver()
                .openAssetFileDescriptor(Uri.parse(record.getLocation()), "r")) {
            return ignored != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static List<ArchiveRecord> readRecords(Context context) {
        List<ArchiveRecord> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(
                    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .getString(KEY_RECORDS, "[]")
            );
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                records.add(new ArchiveRecord(
                        item.getString("location"),
                        item.optBoolean("contentUri", false),
                        item.optString("displayName", "题库.zip"),
                        item.optLong("createdAt", 0),
                        item.optLong("size", 0),
                        item.optString("sourceBankId", "")
                ));
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    private static void writeRecords(Context context, List<ArchiveRecord> records) {
        JSONArray array = new JSONArray();
        try {
            for (ArchiveRecord record : records) {
                JSONObject item = new JSONObject();
                item.put("location", record.getLocation());
                item.put("contentUri", record.isContentUri());
                item.put("displayName", record.getDisplayName());
                item.put("createdAt", record.getCreatedAt());
                item.put("size", record.getSize());
                item.put("sourceBankId", record.getSourceBankId());
                array.put(item);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存下载库记录", e);
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECORDS, array.toString())
                .apply();
    }

    public static final class ArchiveRecord {
        private final String location;
        private final boolean contentUri;
        private final String displayName;
        private final long createdAt;
        private final long size;
        private final String sourceBankId;

        ArchiveRecord(
                String location,
                boolean contentUri,
                String displayName,
                long createdAt,
                long size,
                String sourceBankId
        ) {
            this.location = location;
            this.contentUri = contentUri;
            this.displayName = displayName;
            this.createdAt = createdAt;
            this.size = size;
            this.sourceBankId = sourceBankId == null ? "" : sourceBankId;
        }

        public String getLocation() {
            return location;
        }

        public boolean isContentUri() {
            return contentUri;
        }

        public String getDisplayName() {
            return displayName;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getSize() {
            return size;
        }

        public String getSourceBankId() {
            return sourceBankId;
        }
    }
}
