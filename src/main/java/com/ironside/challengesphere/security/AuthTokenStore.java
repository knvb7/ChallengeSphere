package com.ironside.challengesphere.security;

import com.ironside.challengesphere.persistence.dynamodb.ephemeral.EphemeralStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuthTokenStore {

    private static final Logger logger = LogManager.getLogger(AuthTokenStore.class);

    private final EphemeralStore ephemeralStore;

    public AuthTokenStore(EphemeralStore ephemeralStore) {
        this.ephemeralStore = ephemeralStore;
    }

    public void store(String token, String type, String userId, Duration ttl) {
        Instant now = Instant.now();
        try {
            ephemeralStore.putAuthToken(tokenHash(token), type, userId, now.plus(ttl));
        } catch (RuntimeException exception) {
            logger.error("Auth token store failed for userId={}", userId);
            throw exception;
        }
    }

    public boolean exists(String token, String type, String userId) {
        try {
            return ephemeralStore.authTokenExists(
                    tokenHash(token),
                    type,
                    userId,
                    Instant.now()
            );
        } catch (RuntimeException exception) {
            logger.error("Auth token lookup failed for userId={}", userId);
            return false;
        }
    }

    /**
     * Resolves a live token without consuming it. Callers must keep the token
     * as the final retry credential until their durable mutation succeeds.
     */
    public Optional<String> findUser(String token, String type) {
        try {
            return ephemeralStore.findAuthTokenUser(
                    tokenHash(token),
                    type,
                    Instant.now()
            );
        } catch (RuntimeException exception) {
            logger.error("Auth token lookup failed for type={}", type, exception);
            throw exception;
        }
    }

    public void revoke(String token, String type) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            ephemeralStore.revokeAuthToken(tokenHash(token), type);
        } catch (RuntimeException exception) {
            logger.error("Auth token revoke failed for type={}", type, exception);
            throw exception;
        }
    }

    public Optional<String> consume(String token, String type) {
        try {
            return ephemeralStore.consumeAuthToken(tokenHash(token), type, Instant.now());
        } catch (RuntimeException exception) {
            logger.error("Auth token consume failed for type={}", type);
            return Optional.empty();
        }
    }

    public void revokeAllForUser(String userId) {
        if (userId == null) {
            return;
        }
        try {
            ephemeralStore.revokeAllAuthTokens(userId);
        } catch (RuntimeException exception) {
            logger.error("Auth token revoke-all failed for userId={}", userId, exception);
            throw exception;
        }
    }

    public void revokeAllForUserExcept(String userId, String token, String type) {
        if (userId == null) {
            return;
        }
        try {
            ephemeralStore.revokeAllAuthTokensExcept(
                    userId,
                    tokenHash(token),
                    type
            );
        } catch (RuntimeException exception) {
            logger.error(
                    "Auth token revoke-all-except failed for userId={} type={}",
                    userId,
                    type,
                    exception
            );
            throw exception;
        }
    }

    private String tokenHash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        return sha256(token);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                hex.append(String.format("%02x", part));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
