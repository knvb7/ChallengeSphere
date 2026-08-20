package com.ironside.challengesphere.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountDeletionRetentionScheduler {

    private static final Logger logger = LogManager.getLogger(AccountDeletionRetentionScheduler.class);

    private final JdbcTemplate jdbcTemplate;

    public AccountDeletionRetentionScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.account-deletion.identity-cleanup-cron:0 20 3 * * *}", zone = "UTC")
    public void purgeExpiredIdentityHashes() {
        try {
            int purged = jdbcTemplate.update(
                    """
                    UPDATE challengesphere.account_deletion_requests
                    SET email_identity_hmac = NULL,
                        username_identity_hmac = NULL,
                        identity_hash_purged_at = CURRENT_TIMESTAMP
                    WHERE identity_hash_expires_at <= CURRENT_TIMESTAMP
                      AND (email_identity_hmac IS NOT NULL OR username_identity_hmac IS NOT NULL)
                    """
            );
            if (purged > 0) {
                logger.info("Purged pseudonymous identity fingerprints from {} deletion record(s)", purged);
            }
        } catch (Exception exception) {
            logger.error(
                    "Unable to purge expired account-deletion identity fingerprints: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
