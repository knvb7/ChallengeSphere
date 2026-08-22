package com.ironside.challengesphere.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.challenge")
public class ChallengeParticipationProperties {

    @Min(0)
    @Max(100)
    private int freeMaxActivePerVisibility = 5;

    @Min(0)
    @Max(100)
    private int premiumMaxActivePerVisibility = 20;

    public int getFreeMaxActivePerVisibility() {
        return freeMaxActivePerVisibility == 0 ? 5 : freeMaxActivePerVisibility;
    }

    public int getPremiumMaxActivePerVisibility() {
        return premiumMaxActivePerVisibility == 0 ? 20 : premiumMaxActivePerVisibility;
    }

    public int limitFor(boolean premiumUser) {
        return premiumUser
                ? getPremiumMaxActivePerVisibility()
                : getFreeMaxActivePerVisibility();
    }
}
