package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore.ClaimedAppleTokenRevocation;
import com.ironside.challengesphere.service.impl.AppleSignInService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class AppleTokenRevocationScheduler {

    private static final Logger logger = LogManager.getLogger(AppleTokenRevocationScheduler.class);
    private static final int BATCH_SIZE = 20;

    private final DynamoOperationalStore operationalStore;
    private final AppleSignInService appleSignInService;
    private final Clock clock;

    public AppleTokenRevocationScheduler(
            DynamoOperationalStore operationalStore,
            AppleSignInService appleSignInService,
            Clock clock) {
        this.operationalStore = operationalStore;
        this.appleSignInService = appleSignInService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${apple.signin.revocation.fixed-delay-ms:60000}")
    public void processPendingRevocations() {
        for (ClaimedAppleTokenRevocation revocation
                : operationalStore.claimDueAppleTokenRevocations(BATCH_SIZE)) {
            process(revocation);
        }
    }

    private void process(ClaimedAppleTokenRevocation revocation) {
        try {
            appleSignInService.revokeEncryptedRefreshToken(revocation.encryptedToken());
            boolean completed = operationalStore.completeAppleTokenRevocation(revocation);
            if (!completed) {
                logger.warn("Apple token revocation completion lost its lease id={}", revocation.id());
                return;
            }
            logger.info("Completed queued Apple token revocation id={}", revocation.id());
        } catch (Exception error) {
            int nextAttempt = revocation.attempts() + 1;
            long delaySeconds = Math.min(
                    Duration.ofHours(24).toSeconds(),
                    Duration.ofMinutes(1).toSeconds() * (1L << Math.min(nextAttempt, 10))
            );
            String failure = error.getClass().getSimpleName();
            boolean scheduled = operationalStore.retryAppleTokenRevocation(
                    revocation,
                    Instant.now(clock).plusSeconds(delaySeconds),
                    failure
            );
            if (scheduled) {
                logger.warn("Queued Apple token revocation id={} will retry after {} seconds",
                        revocation.id(), delaySeconds);
            } else {
                logger.warn("Apple token revocation retry lost its lease id={}", revocation.id());
            }
        }
    }
}
