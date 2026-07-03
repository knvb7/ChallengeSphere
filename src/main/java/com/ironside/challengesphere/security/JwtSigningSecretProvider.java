package com.ironside.challengesphere.security;

import com.ironside.challengesphere.config.SsmSecureParameterReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class JwtSigningSecretProvider {

    private final String configuredSecret;
    private final String parameterName;
    private final SsmSecureParameterReader parameterReader;
    private volatile String cachedSecret;

    public JwtSigningSecretProvider(
            @Value("${jwt.secret:}") String configuredSecret,
            @Value("${jwt.secret-parameter-name:}") String parameterName,
            SsmSecureParameterReader parameterReader
    ) {
        this.configuredSecret = configuredSecret;
        this.parameterName = parameterName;
        this.parameterReader = parameterReader;
    }

    public String get() {
        if (StringUtils.hasText(configuredSecret)) {
            return configuredSecret;
        }

        String localSecret = cachedSecret;
        if (StringUtils.hasText(localSecret)) {
            return localSecret;
        }

        synchronized (this) {
            if (!StringUtils.hasText(cachedSecret)) {
                cachedSecret = parameterReader.read(parameterName);
            }
            return cachedSecret;
        }
    }
}
