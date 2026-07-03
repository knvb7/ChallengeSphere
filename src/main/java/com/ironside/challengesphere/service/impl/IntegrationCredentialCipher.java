package com.ironside.challengesphere.service.impl;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class IntegrationCredentialCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final IntegrationCredentialKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public IntegrationCredentialCipher(IntegrationCredentialKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public String encrypt(Long userId, String provider, String field, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext == null ? null : "";
        }
        if (isEncrypted(plaintext)) {
            return plaintext;
        }

        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(context(userId, provider, field));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt integration credential");
        }
    }

    public String decrypt(Long userId, String provider, String field, String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return encryptedValue == null ? null : "";
        }
        if (!isEncrypted(encryptedValue)) {
            throw new IllegalStateException("Integration credential is not encrypted");
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(encryptedValue.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalStateException("Integration credential ciphertext is malformed");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(context(userId, provider, field));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt integration credential");
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private byte[] context(Long userId, String provider, String field) {
        if (userId == null || provider == null || provider.isBlank() || field == null || field.isBlank()) {
            throw new IllegalArgumentException("Credential encryption context is incomplete");
        }
        return ("v1|" + userId + "|" + provider + "|" + field)
                .getBytes(StandardCharsets.UTF_8);
    }
}
