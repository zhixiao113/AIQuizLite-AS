package com.example.aiquizlite;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class MaterialDraftStore {
    public static final long RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final String FILE_NAME = "material_workspace_draft.json";

    private final File file;
    private final Context context;

    public MaterialDraftStore(Context context) {
        this.context = context.getApplicationContext();
        file = new File(context.getFilesDir(), FILE_NAME);
    }

    public synchronized void save(JSONObject state) throws Exception {
        JSONObject copy = new JSONObject(state == null ? "{}" : state.toString());
        copy.put("updatedAt", System.currentTimeMillis());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(copy.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("无法更新资料转题草稿");
        }
        if (!temporary.renameTo(file)) {
            throw new IllegalStateException("无法保存资料转题草稿");
        }
    }

    public synchronized JSONObject load() {
        if (!file.exists()) {
            return null;
        }
        try {
            JSONObject value = new JSONObject(read(file));
            long updatedAt = value.optLong("updatedAt", 0L);
            if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > RETENTION_MS) {
                clear();
                return null;
            }
            return value;
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    public synchronized void clear() {
        DocxMediaStore.clear(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        if (temporary.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static String read(File source) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
