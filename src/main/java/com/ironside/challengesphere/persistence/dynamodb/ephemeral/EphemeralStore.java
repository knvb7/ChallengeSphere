package com.ironside.challengesphere.persistence.dynamodb.ephemeral;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for short-lived, security-sensitive application state.
 *
 * <p>Implementations must enforce expiry while reading; expiry-based database
 * cleanup is only a storage optimization and must never be treated as the
 * authorization decision.</p>
 */
public interface EphemeralStore {

    void putAuthToken(String tokenHash, String tokenType, String userId, Instant expiresAt);

    boolean authTokenExists(String tokenHash, String tokenType, String userId, Instant now);

    Optional<String> findAuthTokenUser(String tokenHash, String tokenType, Instant now);

    Optional<String> consumeAuthToken(String tokenHash, String tokenType, Instant now);

    void revokeAuthToken(String tokenHash, String tokenType);

    void revokeAllAuthTokens(String userId);

    void revokeAllAuthTokensExcept(
            String userId,
            String preservedTokenHash,
            String preservedTokenType
    );

    RateCounterResult consumeRateLimit(String key, int maximum, Duration window, Instant now);

    boolean reserveUploadQuota(
            String userId,
            LocalDate utcDate,
            long requestedObjects,
            long requestedBytes,
            long maximumObjects,
            long maximumBytes,
            Instant expiresAt,
            Instant now
    );

    boolean putGrant(
            String userId,
            String objectKey,
            String purpose,
            Instant expiresAt,
            Instant now
    );

    Optional<Grant> getGrant(String userId, String objectKey, Instant now);

    boolean consumeGrants(String userId, List<String> objectKeys, String purpose, Instant now);

    void revokeGrant(String userId, String objectKey);

    boolean reserveNotificationQuota(
            String recipientUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            boolean deduplicated,
            int maximum,
            Instant expiresAt,
            Instant now
    );

    void releaseNotificationQuota(
            String recipientUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            boolean deduplicated,
            Instant now
    );

    record RateCounterResult(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    record Grant(String purpose, String objectKey, Instant expiresAt) {
    }
}
