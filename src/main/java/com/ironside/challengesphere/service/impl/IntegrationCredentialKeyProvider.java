package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.config.SsmSecureParameterReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Component
public class IntegrationCredentialKeyProvider {

    private static final int AES_256_KEY_BYTES = 32;

    private final String inlineKeyBase64;
    private final String parameterName;
    private final SsmSecureParameterReader parameterReader;
    private volatile SecretKey cachedKey;

    public IntegrationCredentialKeyProvider(
            @Value("${app.integrations.credentials.master-key-base64:}") String inlineKeyBase64,
            @Value("${app.integrations.credentials.parameter-name:}") String parameterName,
            SsmSecureParameterReader parameterReader
    ) {
        this.inlineKeyBase64 = inlineKeyBase64;
        this.parameterName = parameterName;
        this.parameterReader = parameterReader;
    }

    public SecretKey getKey() {
        SecretKey local = cachedKey;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedKey == null) {
                cachedKey = decodeKey(resolveKeyBase64());
            }
            return cachedKey;
        }
    }

    private String resolveKeyBase64() {
        if (inlineKeyBase64 != null && !inlineKeyBase64.isBlank()) {
            return inlineKeyBase64.trim();
        }
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalStateException(
                    "Integration credential encryption key is not configured"
            );
        }

        return parameterReader.read(parameterName);
    }

    private SecretKey decodeKey(String encoded) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Integration credential encryption key is not valid Base64");
        }
        if (keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException("Integration credential encryption key must be 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
