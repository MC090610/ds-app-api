package com.deepseek.chat.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the Platform login token encrypted by the Android Keystore. */
final class PlatformTokenStore {
    private static final String PREFS = "deepseek_platform_auth";
    private static final String TOKEN = "token";
    private static final String KEY_ALIAS = "deepseek_platform_token_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private PlatformTokenStore() {}

    static synchronized void save(Context context, String token) {
        if (token == null || token.isEmpty()) {
            clear(context);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            preferences(context).edit().putString(TOKEN, value).apply();
        } catch (Exception error) {
            clear(context);
        }
    }

    static synchronized String load(Context context) {
        String stored = preferences(context).getString(TOKEN, null);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        int separator = stored.indexOf(':');
        if (separator <= 0 || separator >= stored.length() - 1) {
            clear(context);
            return null;
        }
        try {
            byte[] iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(128, iv)
            );
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            clear(context);
            return null;
        }
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().remove(TOKEN).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );
        generator.init(
            new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        );
        return generator.generateKey();
    }
}
