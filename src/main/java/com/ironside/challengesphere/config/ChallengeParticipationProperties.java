package com.ironside.challengesphere.config;

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

    @Min(1)
    private int maxActiveParticipations = 10;
}
