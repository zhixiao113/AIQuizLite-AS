package com.example.aiquizlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ApiConfigStore {
    static final String PREF_NAME = "ai_config";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_ENCRYPTED_API_KEY = "encrypted_api_key";
    private static final String KEY_API_KEY_IV = "api_key_iv";
    private static final String KEY_LAST_TEST_FINGERPRINT = "last_test_fingerprint";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "aiquizlite_api_key_v1";
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final SharedPreferences preferences;
    private final String prefix;
    private String key(String name) { return prefix + name; }

    public ApiConfigStore(Context context) {
        this(context, "default");
    }

    public ApiConfigStore(Context context, String profileId) {
        prefix = "default".equals(profileId) ? "" : profileId + ":";
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public ApiConfig getConfig() {
        return new ApiConfig(
                preferences.getString(key(KEY_BASE_URL), ""),
                decryptStoredApiKey(),
                preferences.getString(key(KEY_MODEL), "")
        );
    }

    public boolean hasStoredApiKey() {
        return preferences.contains(key(KEY_ENCRYPTED_API_KEY))
                && preferences.contains(key(KEY_API_KEY_IV))
                && !decryptStoredApiKey().isEmpty();
    }

    public void save(String baseUrl, String newApiKey, String model) {
        String normalizedBaseUrl = AiEndpoint.normalizeBaseUrl(baseUrl);
        String normalizedModel = model == null ? "" : model.trim();
        String normalizedNewKey = newApiKey == null ? "" : newApiKey.trim();
        String oldBaseUrl = preferences.getString(key(KEY_BASE_URL), "");
        String oldModel = preferences.getString(key(KEY_MODEL), "");
        if (!oldBaseUrl.isEmpty() && !normalizedBaseUrl.equals(oldBaseUrl) && normalizedNewKey.isEmpty()) {
            throw new IllegalArgumentException("更换 API 地址后请重新输入此服务的 API Key");
        }
        boolean changed = !normalizedBaseUrl.equals(oldBaseUrl)
                || !normalizedModel.equals(oldModel)
                || !normalizedNewKey.isEmpty();

        SharedPreferences.Editor editor = preferences.edit()
                .putString(key(KEY_BASE_URL), normalizedBaseUrl)
                .putString(key(KEY_MODEL), normalizedModel);
        if (!normalizedNewKey.isEmpty()) {
            EncryptedValue encryptedValue = encrypt(normalizedNewKey);
            editor.putString(key(KEY_ENCRYPTED_API_KEY), encryptedValue.value);
            editor.putString(key(KEY_API_KEY_IV), encryptedValue.iv);
        }
        if (changed) {
            editor.remove(key(KEY_LAST_TEST_FINGERPRINT));
        }
        editor.apply();
    }

    public void clearApiKey() {
        preferences.edit()
                .remove(key(KEY_ENCRYPTED_API_KEY))
                .remove(key(KEY_API_KEY_IV))
                .remove(key(KEY_LAST_TEST_FINGERPRINT))
                .apply();
    }

    public void markConnectionSuccessful(ApiConfig config) {
        preferences.edit()
                .putString(key(KEY_LAST_TEST_FINGERPRINT), fingerprint(config))
                .apply();
    }

    public boolean wasConnectionSuccessful(ApiConfig config) {
        String saved = preferences.getString(key(KEY_LAST_TEST_FINGERPRINT), "");
        return config != null && config.isComplete() && saved.equals(fingerprint(config));
    }

    public static String fingerprint(ApiConfig config) {
        if (config == null) {
            return "";
        }
        return sha256(
                AiEndpoint.normalizeBaseUrl(config.getBaseUrl())
                        + "\n" + config.getModel().trim()
                        + "\n" + config.getApiKey()
        );
    }

    private String decryptStoredApiKey() {
        String encrypted = preferences.getString(key(KEY_ENCRYPTED_API_KEY), "");
        String iv = preferences.getString(key(KEY_API_KEY_IV), "");
        if (encrypted.isEmpty() || iv.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            );
            byte[] plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private EncryptedValue encrypt(String apiKey) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.encodeToString(encrypted, Base64.NO_WRAP),
                    Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法加密保存API Key", e);
        }
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class EncryptedValue {
        private final String value;
        private final String iv;

        private EncryptedValue(String value, String iv) {
            this.value = value;
            this.iv = iv;
        }
    }
}
