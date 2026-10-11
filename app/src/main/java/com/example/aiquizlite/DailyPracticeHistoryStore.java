package com.example.aiquizlite;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Details are loaded on demand, rather than putting whole question banks in preferences. */
final class DailyPracticeHistoryStore {
    static final String DIRECTORY = "daily_practice_history";
    private final File directory;

    DailyPracticeHistoryStore(Context context) {
        this(new File(context.getFilesDir(), DIRECTORY));
    }

    DailyPracticeHistoryStore(File directory) { this.directory = directory; }

    void save(DailyPracticeSnapshot snapshot) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建记录目录");
        File target = file(snapshot.id);
        File temp = new File(directory, snapshot.id + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(snapshot.toJson().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (!temp.renameTo(target)) throw new IOException("无法保存答题明细");
        } finally { if (temp.exists()) temp.delete(); }
    }

    DailyPracticeSnapshot load(String id) {
        try (FileInputStream input = new FileInputStream(file(id));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            DailyPracticeSnapshot snapshot = DailyPracticeSnapshot.fromJson(
                    new String(output.toByteArray(), StandardCharsets.UTF_8));
            return snapshot.id.equals(id) ? snapshot : null;
        } catch (Exception error) { return null; }
    }

    void delete(String id) {
        if (id == null || id.isEmpty()) return;
        try { file(id).delete(); } catch (IllegalArgumentException ignored) { }
    }

    void clear() {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
    }

    private File file(String id) {
        if (id == null || !id.matches("[a-fA-F0-9-]{36}")) throw new IllegalArgumentException("无效记录标识");
        return new File(directory, id + ".json");
    }
}
