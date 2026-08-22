package com.ironside.challengesphere.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.revenuecat")
public class RevenueCatProperties {
    private boolean enabled = false;
    private String apiBaseUrl = "https://api.revenuecat.com";
    private String secretApiKey = "";
    private String entitlementId = "premium";
    private String appUserIdPrefix = "cs_user_";
    private String webhookAuthorization = "";
    private String webhookSigningSecret = "";
    private long webhookTimestampToleranceSeconds = 300;

    public boolean isPurchasesReady() {
        return enabled
                && !secretApiKey.isBlank()
                && (!webhookAuthorization.isBlank() || !webhookSigningSecret.isBlank());
    }
}
