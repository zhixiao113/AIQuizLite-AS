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
        this(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE), profileId);
    }

    ApiConfigStore(SharedPreferences preferences, String profileId) {
        prefix = "default".equals(profileId) ? "" : profileId + ":";
        this.preferences = preferences;
    }

    public ApiConfig getConfig() {
        return new ApiConfig(
                preferences.getString(key(KEY_BASE_URL), ""),
                decryptStoredApiKey(),
                preferences.getString(key(KEY_MODEL), "")
        );
    }

    boolean hasSavedRecord() {
        return !preferences.getString(key(KEY_BASE_URL), "").isEmpty()
                || !preferences.getString(key(KEY_MODEL), "").isEmpty()
                || preferences.contains(key(KEY_ENCRYPTED_API_KEY));
    }

    void writeTo(SharedPreferences.Editor editor, ApiConfig config) {
        editor.putString(key(KEY_BASE_URL), AiEndpoint.normalizeBaseUrl(config.getBaseUrl()))
                .putString(key(KEY_MODEL), config.getModel());
        if (!config.getApiKey().equals(decryptStoredApiKey())) {
            EncryptedValue encryptedValue = encrypt(config.getApiKey());
            editor.putString(key(KEY_ENCRYPTED_API_KEY), encryptedValue.value);
            editor.putString(key(KEY_API_KEY_IV), encryptedValue.iv);
        }
        editor.remove(key(KEY_LAST_TEST_FINGERPRINT));
    }

    void removeFrom(SharedPreferences.Editor editor) {
        editor.remove(key(KEY_BASE_URL))
                .remove(key(KEY_MODEL))
                .remove(key(KEY_ENCRYPTED_API_KEY))
                .remove(key(KEY_API_KEY_IV))
                .remove(key(KEY_LAST_TEST_FINGERPRINT));
    }

    public static String fingerprint(ApiConfig config) {
        if (config == null) {
            return "";
        }
        return sha256(fingerprintPart(AiEndpoint.normalizeBaseUrl(config.getBaseUrl()))
                + fingerprintPart(config.getModel()) + fingerprintPart(config.getApiKey()));
    }

    private static String fingerprintPart(String value) {
        return value.length() + ":" + value;
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
                    getSecretKey(false),
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
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(true));
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.encodeToString(encrypted, Base64.NO_WRAP),
                    Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法加密保存API Key", e);
        }
    }

    private SecretKey getSecretKey(boolean create) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        if (!create) throw new java.security.KeyStoreException("Missing API key encryption key");
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
            throw new IllegalStateException("无法校验 AI 配置", e);
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
