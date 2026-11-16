package io.github.externalvnc.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-backed AES-GCM vault. Public GitHub source/APK contains no credentials. */
final class CredentialVault {
    private static final String ALIAS = "oppo_pad_vnc_credentials_v1";
    private final SharedPreferences encrypted;

    CredentialVault(Context context) {
        encrypted = context.getSharedPreferences("vnc_encrypted_credentials_v1", Context.MODE_PRIVATE);
    }

    boolean hasSavedPassword(String profileId) { return encrypted.contains(profileId); }

    private SecretKey secretKey() throws GeneralSecurityException {
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        try { keystore.load(null); }
        catch (java.io.IOException error) { throw new GeneralSecurityException("Keystore unavailable", error); }
        java.security.Key existing = keystore.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    void save(String profileId, String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            remove(profileId);
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        cipher.updateAAD(profileId.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] packed = new byte[1 + iv.length + ciphertext.length];
        packed[0] = (byte) iv.length;
        System.arraycopy(iv, 0, packed, 1, iv.length);
        System.arraycopy(ciphertext, 0, packed, 1 + iv.length, ciphertext.length);
        if (!encrypted.edit().putString(profileId, Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
            throw new GeneralSecurityException("Unable to persist encrypted password");
    }

    String load(String profileId) throws GeneralSecurityException {
        String value = encrypted.getString(profileId, null);
        if (value == null) return null;
        try {
            byte[] packed = Base64.decode(value, Base64.DEFAULT);
            int ivLength = packed[0] & 255;
            if (ivLength != 12 || packed.length < 1 + ivLength + 16)
                throw new GeneralSecurityException("Invalid encrypted record");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                    new GCMParameterSpec(128, packed, 1, ivLength));
            cipher.updateAAD(profileId.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(packed, 1 + ivLength,
                    packed.length - 1 - ivLength);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException error) {
            throw new GeneralSecurityException("Corrupted encrypted record", error);
        }
    }

    void remove(String profileId) { encrypted.edit().remove(profileId).apply(); }
}
