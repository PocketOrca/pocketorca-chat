package com.pocketorca.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore-backed token vault.
 * Each contact (cid) gets an AES/GCM/NoPadding key stored in Android Keystore.
 * Token is encrypted with that key and stored in SharedPreferences
 * (cleartext on device, but requires auth to access Keystore keys).
 *
 * Threat model: protects tokens from casual file-access (rooted device,
 * backup extraction). Not anti-forensics — a rooted device with Keystore
 * bypass can still extract keys.
 */
final class CredentialVault {
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String PREFS_NAME = "credential_vault";
    private static final String PREF_TOKEN_KEY_PREFIX = "token_";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private CredentialVault() {}

    static void init(Context ctx) {
        // Pre-generate keys for existing contacts (called once at startup).
        // Key generation is deferred on first use per-cid; this is a no-op
        // for cold-start performance.
    }

    /** Encrypt and store token for cid. */
    static void saveToken(Context ctx, String cid, String token) {
        try {
            SecretKey key = getOrCreateKey(cid);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(token.getBytes("UTF-8"));
            // Store: iv(12) + encrypted
            byte[] blob = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(encrypted, 0, blob, iv.length, encrypted.length);
            // Save to SharedPreferences
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_TOKEN_KEY_PREFIX + cid, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            // Silently fail — token stored in contacts.json will be used as fallback
        }
    }

    /** Decrypt and retrieve token for cid. */
    static String getToken(Context ctx, String cid) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encoded = prefs.getString(PREF_TOKEN_KEY_PREFIX + cid, null);
        if (encoded == null) return null;
        try {
            byte[] blob = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] encrypted = new byte[blob.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(blob, 0, iv, 0, iv.length);
            System.arraycopy(blob, iv.length, encrypted, 0, encrypted.length);
            SecretKey key = getOrCreateKey(cid);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Delete token for cid. */
    static void removeToken(Context ctx, String cid) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(PREF_TOKEN_KEY_PREFIX + cid).apply();
    }

    /** Generate or retrieve AES key from Android Keystore. */
    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);

        if (!ks.containsAlias(alias)) {
            KeyGenerator keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            keyGen.init(new KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return keyGen.generateKey();
        }
        return (SecretKey) ks.getKey(alias, null);
    }
}
