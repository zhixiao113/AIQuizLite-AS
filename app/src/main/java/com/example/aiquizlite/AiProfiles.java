package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Registry contains identifiers only; each provider key uses ApiConfigStore encryption. */
public final class AiProfiles {
    private final Context context;
    private final SharedPreferences prefs;
    public AiProfiles(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(ApiConfigStore.PREF_NAME, Context.MODE_PRIVATE);
    }
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        ids.add("default");
        ids.addAll(new java.util.TreeSet<>(prefs.getStringSet("profiles", java.util.Collections.emptySet())));
        return ids;
    }
    public String name(String id) { return id == null || id.isEmpty() ? "未指定" : prefs.getString("name_" + id, "原有 AI"); }
    public String add(String name) {
        String id = java.util.UUID.randomUUID().toString();
        java.util.Set<String> ids = new java.util.HashSet<>(prefs.getStringSet("profiles", java.util.Collections.emptySet()));
        ids.add(id);
        prefs.edit().putStringSet("profiles", ids).putString("name_" + id, name).apply();
        return id;
    }
    public boolean enabled(String id) { return ids().contains(id) && prefs.getBoolean("enabled_" + id, true); }
    public void enable(String id, boolean enabled) { prefs.edit().putBoolean("enabled_" + id, enabled).apply(); }
    public String role(String role) { return prefs.getString("role_" + role, "text".equals(role) ? "default" : ""); }
    public void assign(String role, String id) {
        if (!"text".equals(role) && !"vision".equals(role)) throw new IllegalArgumentException("未知 AI 分工");
        if (!id.isEmpty() && (!enabled(id) || !new ApiConfigStore(context, id).getConfig().isComplete()))
            throw new IllegalArgumentException("请先保存完整配置并启用此 AI");
        prefs.edit().putString("role_" + role, id).apply();
    }
    public void rename(String id, String name) { prefs.edit().putString("name_" + id, name).apply(); }
    public void delete(String id) {
        if ("default".equals(id)) throw new IllegalArgumentException("原有 AI 请使用清除 Key 或停用");
        java.util.Set<String> ids = new java.util.HashSet<>(prefs.getStringSet("profiles", java.util.Collections.emptySet()));
        ids.remove(id);
        SharedPreferences.Editor editor = prefs.edit().putStringSet("profiles", ids);
        for (String key : prefs.getAll().keySet()) if (key.startsWith(id + ":")) editor.remove(key);
        editor.remove("name_" + id).remove("enabled_" + id);
        if (id.equals(role("text"))) editor.putString("role_text", "");
        if (id.equals(role("vision"))) editor.putString("role_vision", "");
        editor.apply();
    }
    public String importEntries(List<AiProfileImport.Entry> entries) {
        if (ids().size() + entries.size() > 50) throw new IllegalArgumentException("最多保留 50 个 AI 配置，请先删除不用的配置");
        java.util.Set<String> ids = new java.util.HashSet<>(prefs.getStringSet("profiles", java.util.Collections.emptySet()));
        List<String> created = new ArrayList<>();
        try {
            for (AiProfileImport.Entry entry : entries) {
                String id = java.util.UUID.randomUUID().toString();
                created.add(id);
                new ApiConfigStore(context, id).save(entry.config.getBaseUrl(), entry.config.getApiKey(), entry.config.getModel());
            }
            SharedPreferences.Editor editor = prefs.edit();
            for (int i = 0; i < entries.size(); i++) {
                AiProfileImport.Entry entry = entries.get(i);
                String id = created.get(i);
                ids.add(id);
                editor.putString("name_" + id, entry.name).putBoolean("enabled_" + id, entry.enabled);
                if (entry.text) editor.putString("role_text", id);
                if (entry.vision) editor.putString("role_vision", id);
            }
            editor.putStringSet("profiles", ids).apply();
            return created.get(0);
        } catch (RuntimeException failure) {
            SharedPreferences.Editor cleanup = prefs.edit();
            for (String key : prefs.getAll().keySet()) for (String id : created)
                if (key.startsWith(id + ":")) cleanup.remove(key);
            cleanup.apply();
            throw new IllegalStateException("AI 配置导入失败，未修改现有分工，请重试");
        }
    }
    public ApiConfig config(String role) {
        String id = role(role);
        return id.isEmpty() || !enabled(id) ? new ApiConfig("", "", "") : new ApiConfigStore(context, id).getConfig();
    }
}
