package com.ironside.challengesphere.security;

import com.ironside.challengesphere.persistence.dynamodb.ephemeral.EphemeralStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Application-facing DynamoDB rate-limit adapter. The historical class name is
 * retained only to avoid changing existing injection points.
 */
@Service
public class RedisRateLimitStore implements RateLimitStore {

    private final EphemeralStore ephemeralStore;

    public RedisRateLimitStore(EphemeralStore ephemeralStore) {
        this.ephemeralStore = ephemeralStore;
    }

    @Override
    public Decision consume(String key, int maxRequests, Duration window) {
        if (key == null || key.isBlank() || maxRequests < 1 || window == null
                || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit configuration is invalid");
        }

        EphemeralStore.RateCounterResult result = ephemeralStore.consumeRateLimit(
                key,
                maxRequests,
                window,
                Instant.now()
        );
        return new Decision(
                result.allowed(),
                result.remaining(),
                result.retryAfterSeconds()
        );
    }
}
