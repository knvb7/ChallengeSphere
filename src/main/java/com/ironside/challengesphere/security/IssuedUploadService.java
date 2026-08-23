package com.ironside.challengesphere.security;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.persistence.dynamodb.ephemeral.EphemeralStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Records newly issued upload keys and atomically consumes them when media is
 * attached. Keys therefore cannot be fabricated, replayed, or attached twice.
 */
@Service
public class IssuedUploadService {

    public enum Purpose {
        SUBMISSION_PHOTO,
        CHALLENGE_COVER,
        PROFILE_PHOTO,
        SUPPORT_SCREENSHOT
    }

    private static final Logger logger = LogManager.getLogger(IssuedUploadService.class);

    private final EphemeralStore ephemeralStore;
    private final Duration ticketTtl;

    public IssuedUploadService(
            EphemeralStore ephemeralStore,
            @Value("${app.security.upload.ticket-ttl-minutes:60}") long ticketTtlMinutes
    ) {
        if (ticketTtlMinutes < 1 || ticketTtlMinutes > 1_440) {
            throw new IllegalStateException("Upload ticket TTL must be between 1 and 1440 minutes");
        }
        this.ephemeralStore = ephemeralStore;
        this.ticketTtl = Duration.ofMinutes(ticketTtlMinutes);
    }

    public void issue(String userId, String objectKey, Purpose purpose)
            throws ChallengeSphereException {
        validate(userId, objectKey, purpose);
        Instant now = Instant.now();
        try {
            boolean issued = ephemeralStore.putGrant(
                    userId,
                    objectKey,
                    purpose.name(),
                    now.plus(ticketTtl),
                    now
            );
            if (!issued) {
                throw new ChallengeSphereException(
                        "Upload key could not be issued",
                        HttpStatus.CONFLICT,
                        logger
                );
            }
        } catch (ChallengeSphereException exception) {
            throw exception;
        } catch (RuntimeException unavailable) {
            logger.error("Upload ticket store is unavailable while issuing for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw unavailable();
        }
    }

    public void consume(String userId, List<String> objectKeys, Purpose purpose)
            throws ChallengeSphereException {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        if (objectKeys.size() > 5 || objectKeys.stream().distinct().count() != objectKeys.size()) {
            throw invalidReference();
        }
        for (String objectKey : objectKeys) {
            validate(userId, objectKey, purpose);
        }

        boolean consumed;
        try {
            consumed = ephemeralStore.consumeGrants(
                    userId,
                    objectKeys,
                    purpose.name(),
                    Instant.now()
            );
        } catch (RuntimeException unavailable) {
            logger.error("Upload ticket store is unavailable while consuming for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw unavailable();
        }
        if (!consumed) {
            throw invalidReference();
        }
    }

    private void validate(String userId, String objectKey, Purpose purpose)
            throws ChallengeSphereException {
        if (userId == null
                || userId.isBlank()
                || purpose == null
                || objectKey == null
                || objectKey.isBlank()
                || objectKey.length() > 256
                || objectKey.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidReference();
        }
    }

    private ChallengeSphereException invalidReference() {
        return new ChallengeSphereException(
                "Upload reference is invalid, expired, or already used",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }

    private ChallengeSphereException unavailable() {
        return new ChallengeSphereException(
                "Upload protection is temporarily unavailable",
                HttpStatus.SERVICE_UNAVAILABLE,
                logger
        );
    }
}
