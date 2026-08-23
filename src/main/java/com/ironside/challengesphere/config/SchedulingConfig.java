package com.ironside.challengesphere.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Keeps background writers out of offline maintenance processes such as the
 * PostgreSQL-to-DynamoDB migration runner.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SchedulingConfig {
}
