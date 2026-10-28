package com.natuu.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the locally stored API key with a key held by Android Keystore. */
public final class SecureStore {
    private static final String PREFS = "natuu_secure_prefs";
    private static final String KEY_API = "gemini_api_key_v1";
    private static final String KEY_VOICE_API = "elevenlabs_api_key_v1";
    private static final String KEY_VOICE_ID = "elevenlabs_voice_id_v1";
    private static final String ALIAS = "natuu_gemini_key_aes";

    private SecureStore() {}

    public static void saveApiKey(Context context, String apiKey) throws Exception {
        saveSecret(context, KEY_API, apiKey);
    }

    public static String getApiKey(Context context) {
        return getSecret(context, KEY_API);
    }

    public static void clearApiKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_API).apply();
    }

    public static void saveVoiceApiKey(Context context, String apiKey) throws Exception {
        saveSecret(context, KEY_VOICE_API, apiKey);
    }

    public static String getVoiceApiKey(Context context) {
        return getSecret(context, KEY_VOICE_API);
    }

    public static void clearVoiceApiKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_VOICE_API).apply();
    }

    public static void saveVoiceId(Context context, String voiceId) throws Exception {
        saveSecret(context, KEY_VOICE_ID, voiceId);
    }

    public static String getVoiceId(Context context) {
        return getSecret(context, KEY_VOICE_ID);
    }

    public static void clearVoiceId(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_VOICE_ID).apply();
    }

    private static void saveSecret(Context context, String key, String value) throws Exception {
        byte[] ivAndCiphertext = encrypt(value.trim().getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.encodeToString(ivAndCiphertext, Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, encoded).apply();
    }

    private static String getSecret(Context context, String key) {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null);
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(all);
            int ivLength = buffer.getInt();
            if (ivLength < 12 || ivLength > 16 || ivLength > buffer.remaining()) return "";
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] encrypt(byte[] plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plainText);
        ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        buffer.putInt(iv.length).put(iv).put(encrypted);
        return buffer.array();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
