package com.example.aiquizlite;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the entire paste before any provider or role is saved. Errors never echo credentials. */
final class AiProfileImport {
    static final int MAX_CHARACTERS = 128 * 1024;
    static final int MAX_PROFILES = 20;
    static final class Entry {
        final String name;
        final ApiConfig config;
        final boolean enabled;
        final boolean text;
        final boolean vision;
        Entry(String name, ApiConfig config, boolean enabled, boolean text, boolean vision) {
            this.name = name; this.config = config; this.enabled = enabled; this.text = text; this.vision = vision;
        }
    }
    static List<Entry> parse(String raw) {
        if (raw == null || raw.length() > MAX_CHARACTERS) throw new IllegalArgumentException("导入内容不能为空且不能超过 128 KB");
        try {
            JSONArray array = new JSONArray(raw);
            if (array.length() == 0 || array.length() > MAX_PROFILES) throw new IllegalArgumentException("每次可导入 1–20 个 AI 配置");
            List<Entry> entries = new ArrayList<>();
            Set<String> names = new HashSet<>();
            boolean hasText = false, hasVision = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String name = requiredString(item, "name", 60);
                String url = requiredString(item, "baseUrl", 2048);
                String key = requiredString(item, "apiKey", 8192);
                String model = requiredString(item, "model", 200);
                AiEndpoint.validateHttpsBaseUrl(url);
                if (URI.create(url).getRawUserInfo() != null) throw new IllegalArgumentException("API 地址不能包含用户名或密码");
                if (!names.add(name)) throw new IllegalArgumentException("导入内容的配置名称不能重复");
                boolean enabled = booleanValue(item, "enabled", true);
                boolean text = booleanValue(item, "text", false);
                boolean vision = booleanValue(item, "vision", false);
                if ((text || vision) && !enabled) throw new IllegalArgumentException("指定分工的 AI 必须启用");
                if (text && hasText || vision && hasVision) throw new IllegalArgumentException("每种分工只能指定一个 AI");
                hasText |= text; hasVision |= vision;
                entries.add(new Entry(name, new ApiConfig(AiEndpoint.normalizeBaseUrl(url), key, model), enabled, text, vision));
            }
            return Collections.unmodifiableList(entries);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("JSON 格式不正确，请使用下方示例中的字段"); }
    }
    private static String requiredString(JSONObject item, String key, int maximum) {
        Object value = item.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > maximum)
            throw new IllegalArgumentException("字段 " + key + " 缺失、为空或过长");
        return ((String) value).trim();
    }
    private static boolean booleanValue(JSONObject item, String key, boolean fallback) {
        if (!item.has(key)) return fallback;
        Object value = item.opt(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("字段 " + key + " 必须为 true 或 false");
        return (Boolean) value;
    }
}
