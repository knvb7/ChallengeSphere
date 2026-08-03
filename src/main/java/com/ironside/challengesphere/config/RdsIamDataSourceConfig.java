package com.ironside.challengesphere.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(prefix = "app.datasource.iam-auth", name = "enabled", havingValue = "true")
class RdsIamDataSourceConfig {

    static final long RDS_IAM_SAFE_MAX_LIFETIME_MS = 13 * 60 * 1000L;
    static final long RDS_IAM_SAFE_KEEPALIVE_MS = 5 * 60 * 1000L;
    private static final long HIKARI_MIN_MAX_LIFETIME_MS = 30 * 1000L;

    @Bean
    @Primary
    DataSource dataSource(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${app.datasource.iam-auth.region}") String region,
            @Value("${app.datasource.iam-auth.hostname}") String hostname,
            @Value("${app.datasource.iam-auth.port}") int port,
            @Value("${app.datasource.iam-auth.aws-profile:}") String awsProfile,
            @Value("${spring.datasource.hikari.pool-name:ChallengeSphereProdPool}") String poolName,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:10}") int minimumIdle,
            @Value("${spring.datasource.hikari.connection-timeout:30000}") long connectionTimeout,
            @Value("${spring.datasource.hikari.validation-timeout:5000}") long validationTimeout,
            @Value("${spring.datasource.hikari.idle-timeout:600000}") long idleTimeout,
            @Value("${spring.datasource.hikari.max-lifetime:1800000}") long maxLifetime,
            @Value("${spring.datasource.hikari.keepalive-time:0}") long keepaliveTime
    ) {
        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
        postgresDataSource.setUrl(jdbcUrl);

        IamAuthDataSource iamAuthDataSource = new IamAuthDataSource(
                username,
                new AwsRdsIamAuthTokenProvider(region, hostname, port, awsProfile),
                postgresDataSource::getConnection
        );

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName(poolName);
        hikariConfig.setMaximumPoolSize(maximumPoolSize);
        hikariConfig.setMinimumIdle(minimumIdle);
        hikariConfig.setConnectionTimeout(connectionTimeout);
        hikariConfig.setValidationTimeout(validationTimeout);
        hikariConfig.setIdleTimeout(idleTimeout);
        hikariConfig.setMaxLifetime(resolveIamSafeMaxLifetime(maxLifetime));
        hikariConfig.setKeepaliveTime(resolveIamSafeKeepaliveTime(keepaliveTime));
        hikariConfig.setDataSource(iamAuthDataSource);

        return new HikariDataSource(hikariConfig);
    }

    static long resolveIamSafeMaxLifetime(long configuredMaxLifetime) {
        long requestedMaxLifetime = configuredMaxLifetime > 0
                ? configuredMaxLifetime
                : RDS_IAM_SAFE_MAX_LIFETIME_MS;
        long cappedMaxLifetime = Math.min(requestedMaxLifetime, RDS_IAM_SAFE_MAX_LIFETIME_MS);
        return Math.max(cappedMaxLifetime, HIKARI_MIN_MAX_LIFETIME_MS);
    }

    static long resolveIamSafeKeepaliveTime(long configuredKeepaliveTime) {
        if (configuredKeepaliveTime <= 0) {
            return RDS_IAM_SAFE_KEEPALIVE_MS;
        }
        return Math.min(configuredKeepaliveTime, RDS_IAM_SAFE_KEEPALIVE_MS);
    }
}
