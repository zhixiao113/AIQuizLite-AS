package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.UUID;

/** Creation order and runtime state live beside the existing, Keystore-encrypted configs. */
final class AiProfiles implements AiExplanationRouter.Pool {
    static final long COOLDOWN_MILLIS = 3L * 24 * 60 * 60 * 1000;
    private static final String ORDER = "model_order_v2";
    private static final String LEGACY_IDS = "profiles";
    private static final String DEFAULT = "default";
    private static final Object LOCK = new Object();

    static final class Model {
        final String id;
        final ApiConfig config;
        final boolean enabled;
        final boolean visionSupported;
        final boolean visionTested;
        final long cooldownUntil;
        final long revision;

        Model(String id, ApiConfig config, boolean enabled, boolean visionSupported,
                boolean visionTested, long cooldownUntil, long revision) {
            this.id = id;
            this.config = config;
            this.enabled = enabled;
            this.visionSupported = visionSupported;
            this.visionTested = visionTested;
            this.cooldownUntil = cooldownUntil;
            this.revision = revision;
        }

        boolean isCooling(long now) { return cooldownUntil > now; }
    }

    private final SharedPreferences prefs;

    AiProfiles(Context context) {
        this(context.getSharedPreferences(ApiConfigStore.PREF_NAME, Context.MODE_PRIVATE));
    }

    AiProfiles(SharedPreferences prefs) { this.prefs = prefs; }

    @Override public List<Model> models() {
        synchronized (LOCK) {
            List<Model> result = new ArrayList<>();
            for (String id : orderLocked()) result.add(readLocked(id));
            return result;
        }
    }

    @Override public Model model(String id) {
        synchronized (LOCK) { return orderLocked().contains(id) ? readLocked(id) : null; }
    }

    boolean enable(String id, boolean enabled) {
        synchronized (LOCK) {
            Model model = model(id);
            if (model == null || model.isCooling(System.currentTimeMillis())) return false;
            if (model.enabled == enabled) return true;
            prefs.edit().putBoolean("enabled_" + id, enabled)
                    .putLong("revision_" + id, model.revision + 1).apply();
            return true;
        }
    }

    String save(String id, ApiConfig config, AiModelProbe.Result proof) {
        if (config == null || !config.isComplete() || proof == null || !proof.matches(config)) {
            throw new IllegalArgumentException("请先通过当前配置的连接测试");
        }
        AiEndpoint.validateHttpsBaseUrl(config.getBaseUrl());
        synchronized (LOCK) {
            List<String> order = orderLocked();
            boolean adding = id == null;
            Model old = null;
            if (adding) {
                id = UUID.randomUUID().toString();
                order.add(id);
            } else {
                if (!order.contains(id)) throw new IllegalStateException("此模型配置已删除");
                old = readLocked(id);
            }
            SharedPreferences.Editor editor = prefs.edit();
            new ApiConfigStore(prefs, id).writeTo(editor, config);
            if (adding) writeOrder(editor, order);
            writeProbe(editor, id, proof, old != null && proof.matches(old.config) ? old : null);
            editor.putBoolean("enabled_" + id, adding || old.enabled)
                    .putLong("revision_" + id, old == null ? 1 : old.revision + 1)
                    .remove("cooldown_" + id)
                    .apply();
            return id;
        }
    }

    boolean recoverAfterTest(String id, AiModelProbe.Result proof) {
        synchronized (LOCK) {
            Model saved = model(id);
            if (saved == null || proof == null || !proof.matches(saved.config)) return false;
            boolean recovered = saved.isCooling(System.currentTimeMillis());
            SharedPreferences.Editor editor = prefs.edit();
            writeProbe(editor, id, proof, saved);
            editor.remove("cooldown_" + id).putLong("revision_" + id, saved.revision + 1).apply();
            return recovered;
        }
    }

    private void writeProbe(SharedPreferences.Editor editor, String id, AiModelProbe.Result proof, Model sameConfig) {
        // A timeout during a re-test doesn't erase a known capability of the same config.
        boolean known = proof.visionTested || sameConfig != null && sameConfig.visionTested;
        boolean supported = proof.visionTested ? proof.visionSupported : sameConfig != null && sameConfig.visionSupported;
        editor.putBoolean("vision_tested_" + id, known).putBoolean("vision_supported_" + id, supported);
    }

    @Override public boolean markFailed(Model attempted, long now) {
        synchronized (LOCK) {
            Model saved = model(attempted.id);
            if (saved == null || !saved.enabled || saved.revision != attempted.revision || saved.isCooling(now)) return false;
            prefs.edit().putLong("cooldown_" + saved.id, now + COOLDOWN_MILLIS).apply();
            return true;
        }
    }

    void delete(String id) {
        synchronized (LOCK) {
            List<String> order = orderLocked();
            if (!order.remove(id)) return;
            SharedPreferences.Editor editor = prefs.edit();
            writeOrder(editor, order);
            new ApiConfigStore(prefs, id).removeFrom(editor);
            editor.remove("name_" + id).remove("enabled_" + id).remove("revision_" + id)
                    .remove("vision_tested_" + id).remove("vision_supported_" + id).remove("cooldown_" + id).apply();
        }
    }

    private Model readLocked(String id) {
        return new Model(id, new ApiConfigStore(prefs, id).getConfig(), prefs.getBoolean("enabled_" + id, true),
                prefs.getBoolean("vision_supported_" + id, false), prefs.getBoolean("vision_tested_" + id, false),
                prefs.getLong("cooldown_" + id, 0), prefs.getLong("revision_" + id, 0));
    }

    private List<String> orderLocked() {
        String saved = prefs.getString(ORDER, null);
        if (saved != null) return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split("\n")));
        List<String> migrated = new ArrayList<>();
        if (new ApiConfigStore(prefs, DEFAULT).hasSavedRecord()) migrated.add(DEFAULT);
        Set<String> legacy = prefs.getStringSet(LEGACY_IDS, Collections.emptySet());
        for (String id : new TreeSet<>(legacy)) {
            if (!DEFAULT.equals(id) && new ApiConfigStore(prefs, id).hasSavedRecord()) migrated.add(id);
        }
        SharedPreferences.Editor editor = prefs.edit();
        writeOrder(editor, migrated);
        editor.apply();
        return migrated;
    }

    private void writeOrder(SharedPreferences.Editor editor, List<String> order) {
        Set<String> ids = new HashSet<>(order);
        ids.remove(DEFAULT);
        StringJoiner serializedOrder = new StringJoiner("\n");
        for (String id : order) serializedOrder.add(id);
        editor.putString(ORDER, serializedOrder.toString()).putStringSet(LEGACY_IDS, ids);
    }
}
