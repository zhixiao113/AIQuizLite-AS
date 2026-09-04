package com.example.aiquizlite;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;
import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class BackupArchiveStore {
    public static final String DISPLAY_FOLDER = "Download/AIQuizLite/Backup";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS
            + "/AIQuizLite/Backup/";
    private static final String PREF_NAME = "backup_archive_store";
    private static final String KEY_RECORDS = "records";

    private BackupArchiveStore() {
    }

    public static synchronized ArchiveRecord save(
            Context context,
            FullBackupManager.CreatedBackup created
    ) throws Exception {
        List<ArchiveRecord> records = new ArrayList<>(list(context));
        Set<String> names = new HashSet<>();
        for (ArchiveRecord record : records) names.add(record.displayName);
        String displayName = FullBackupRules.uniqueFileName(
                FullBackupRules.fileName(created.getSummary().getCreatedAt()), names
        );
        ArchiveRecord record = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? saveMediaStore(context, created.getFile(), displayName,
                created.getSummary().getCreatedAt())
                : saveLegacy(created.getFile(), displayName, created.getSummary().getCreatedAt());
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
        records.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        if (changed) writeRecords(context, records);
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public static synchronized void delete(Context context, ArchiveRecord record) throws Exception {
        if (record.contentUri) {
            context.getContentResolver().delete(Uri.parse(record.location), null, null);
        } else {
            File file = new File(record.location);
            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("无法删除备份文件");
            }
        }
        List<ArchiveRecord> records = readRecords(context);
        records.removeIf(item -> item.location.equals(record.location));
        writeRecords(context, records);
    }

    public static InputStream open(Context context, ArchiveRecord record) throws Exception {
        InputStream input = record.contentUri
                ? context.getContentResolver().openInputStream(Uri.parse(record.location))
                : new FileInputStream(new File(record.location));
        if (input == null) throw new IllegalStateException("备份文件不存在或无法读取");
        return input;
    }

    public static Uri shareUri(Context context, ArchiveRecord record) {
        if (record.contentUri) return Uri.parse(record.location);
        return FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                new File(record.location)
        );
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static ArchiveRecord saveMediaStore(
            Context context,
            File source,
            String displayName,
            long createdAt
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("无法在下载目录创建备份");
        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("无法写入备份文件");
                copy(input, output);
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, published, null, null);
            return new ArchiveRecord(uri.toString(), true, displayName, createdAt, source.length());
        } catch (Exception error) {
            resolver.delete(uri, null, null);
            throw error;
        }
    }

    private static ArchiveRecord saveLegacy(
            File source,
            String displayName,
            long createdAt
    ) throws Exception {
        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AIQuizLite/Backup"
        );
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建备份目录");
        }
        File target = new File(directory, displayName);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        target.setLastModified(createdAt);
        return new ArchiveRecord(target.getAbsolutePath(), false, displayName, createdAt, target.length());
    }

    private static boolean exists(Context context, ArchiveRecord record) {
        if (!record.contentUri) return new File(record.location).isFile();
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse(record.location), new String[]{MediaStore.MediaColumns._ID},
                null, null, null
        )) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<ArchiveRecord> readRecords(Context context) {
        List<ArchiveRecord> records = new ArrayList<>();
        try {
            String raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_RECORDS, "[]");
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                records.add(new ArchiveRecord(
                        item.getString("location"),
                        item.optBoolean("contentUri", false),
                        item.optString("displayName", "AIQuizLite 备份.zip"),
                        item.optLong("createdAt", 0L),
                        item.optLong("size", 0L)
                ));
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    @SuppressLint("ApplySharedPref")
    private static void writeRecords(Context context, List<ArchiveRecord> records) {
        JSONArray array = new JSONArray();
        try {
            for (ArchiveRecord record : records) {
                array.put(new JSONObject()
                        .put("location", record.location)
                        .put("contentUri", record.contentUri)
                        .put("displayName", record.displayName)
                        .put("createdAt", record.createdAt)
                        .put("size", record.size));
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法保存备份列表", error);
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECORDS, array.toString()).commit();
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    public static final class ArchiveRecord {
        private final String location;
        private final boolean contentUri;
        private final String displayName;
        private final long createdAt;
        private final long size;

        ArchiveRecord(String location, boolean contentUri, String displayName,
                      long createdAt, long size) {
            this.location = location;
            this.contentUri = contentUri;
            this.displayName = displayName;
            this.createdAt = createdAt;
            this.size = size;
        }

        public String getLocation() { return location; }
        public boolean isContentUri() { return contentUri; }
        public String getDisplayName() { return displayName; }
        public long getCreatedAt() { return createdAt; }
        public long getSize() { return size; }
    }
}
