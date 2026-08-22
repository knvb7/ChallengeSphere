package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.service.impl.AppleSignInService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class AppleTokenRevocationScheduler {

    private static final Logger logger = LogManager.getLogger(AppleTokenRevocationScheduler.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbcTemplate;
    private final AppleSignInService appleSignInService;

    public AppleTokenRevocationScheduler(
            JdbcTemplate jdbcTemplate,
            AppleSignInService appleSignInService) {
        this.jdbcTemplate = jdbcTemplate;
        this.appleSignInService = appleSignInService;
    }

    @Scheduled(fixedDelayString = "${apple.signin.revocation.fixed-delay-ms:60000}")
    public void processPendingRevocations() {
        List<Revocation> pending = jdbcTemplate.query(
                """
                SELECT id, encrypted_token, attempts
                FROM challengesphere.provider_token_revocations
                WHERE provider = 'APPLE'
                  AND status IN ('PENDING', 'RETRY')
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                  AND encrypted_token IS NOT NULL
                ORDER BY created_at
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new Revocation(
                        resultSet.getLong("id"),
                        resultSet.getString("encrypted_token"),
                        resultSet.getInt("attempts")
                ),
                BATCH_SIZE
        );

        for (Revocation revocation : pending) {
            process(revocation);
        }
    }

    private void process(Revocation revocation) {
        try {
            appleSignInService.revokeEncryptedRefreshToken(revocation.encryptedToken());
            jdbcTemplate.update(
                    """
                    UPDATE challengesphere.provider_token_revocations
                    SET status = 'COMPLETED',
                        encrypted_token = NULL,
                        attempts = attempts + 1,
                        completed_at = CURRENT_TIMESTAMP,
                        last_error = NULL
                    WHERE id = ?
                    """,
                    revocation.id()
            );
            logger.info("Completed queued Apple token revocation id={}", revocation.id());
        } catch (Exception error) {
            int nextAttempt = revocation.attempts() + 1;
            long delaySeconds = Math.min(
                    Duration.ofHours(24).toSeconds(),
                    Duration.ofMinutes(1).toSeconds() * (1L << Math.min(nextAttempt, 10))
            );
            String failure = error.getClass().getSimpleName();
            jdbcTemplate.update(
                    """
                    UPDATE challengesphere.provider_token_revocations
                    SET status = 'RETRY',
                        attempts = ?,
                        next_attempt_at = ?,
                        last_error = ?
                    WHERE id = ?
                    """,
                    nextAttempt,
                    Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                    failure,
                    revocation.id()
            );
            logger.warn("Queued Apple token revocation id={} will retry after {} seconds",
                    revocation.id(), delaySeconds);
        }
    }

    private record Revocation(long id, String encryptedToken, int attempts) {}
}
