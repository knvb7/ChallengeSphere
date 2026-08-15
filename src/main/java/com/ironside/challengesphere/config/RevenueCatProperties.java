package com.ironside.challengesphere.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.revenuecat")
public class RevenueCatProperties {
    private final SsmSecureParameterReader parameterReader;

    private boolean enabled = false;
    private String apiBaseUrl = "https://api.revenuecat.com";
    private String secretApiKey = "";
    private String secretApiKeyParameterName = "";
    private String entitlementId = "premium";
    private String appUserIdPrefix = "cs_user_";
    private String webhookAuthorization = "";
    private String webhookAuthorizationParameterName = "";
    private String webhookSigningSecret = "";
    private long webhookTimestampToleranceSeconds = 300;

    public RevenueCatProperties(SsmSecureParameterReader parameterReader) {
        this.parameterReader = parameterReader;
    }

    @PostConstruct
    void resolveProductionSecrets() {
        if (!enabled) return;
        secretApiKey = resolveSecret(
                secretApiKey,
                secretApiKeyParameterName,
                "RevenueCat secret API key"
        );
        webhookAuthorization = resolveSecret(
                webhookAuthorization,
                webhookAuthorizationParameterName,
                "RevenueCat webhook authorization"
        );
    }

    public boolean isPurchasesReady() {
        return enabled
                && !secretApiKey.isBlank()
                && (!webhookAuthorization.isBlank() || !webhookSigningSecret.isBlank());
    }

    private String resolveSecret(String configuredValue, String parameterName, String label) {
        if (StringUtils.hasText(configuredValue)) return configuredValue;
        if (!StringUtils.hasText(parameterName)) {
            throw new IllegalStateException(label + " is not configured");
        }
        String value = parameterReader.read(parameterName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(label + " secure parameter is empty");
        }
        return value;
    }
}
