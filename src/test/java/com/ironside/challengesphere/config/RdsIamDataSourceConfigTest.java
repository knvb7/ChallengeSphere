package com.ironside.challengesphere.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RdsIamDataSourceConfigTest {

    @Test
    void capsConnectionLifetimeBelowIamTokenTtl() {
        long thirtyMinutes = 30 * 60 * 1000L;

        long maxLifetime = RdsIamDataSourceConfig.resolveIamSafeMaxLifetime(thirtyMinutes);

        assertThat(maxLifetime)
                .isEqualTo(RdsIamDataSourceConfig.RDS_IAM_SAFE_MAX_LIFETIME_MS)
                .isLessThan(RdsIamDataSourceConfig.RDS_IAM_TOKEN_TTL_MS);
    }

    @Test
    void keepsExplicitShorterLifetime() {
        long tenMinutes = 10 * 60 * 1000L;

        long maxLifetime = RdsIamDataSourceConfig.resolveIamSafeMaxLifetime(tenMinutes);

        assertThat(maxLifetime).isEqualTo(tenMinutes);
    }

    @Test
    void suppliesSafeKeepaliveWhenDisabled() {
        long keepaliveTime = RdsIamDataSourceConfig.resolveIamSafeKeepaliveTime(0);

        assertThat(keepaliveTime).isEqualTo(RdsIamDataSourceConfig.RDS_IAM_SAFE_KEEPALIVE_MS);
    }
}
