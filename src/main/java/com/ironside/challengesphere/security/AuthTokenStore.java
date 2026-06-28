package com.ironside.challengesphere.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;

@Service
public class AuthTokenStore {

    private static final Logger logger = LogManager.getLogger(AuthTokenStore.class);
    private static final String INSERT_TOKEN_SQL = """
            INSERT INTO challengesphere.auth_tokens (token_hash, token_type, user_id, expires_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (token_hash, token_type)
            DO UPDATE SET
                user_id = EXCLUDED.user_id,
                expires_at = EXCLUDED.expires_at,
                created_at = CURRENT_TIMESTAMP
            """;
    private static final String EXISTS_SQL = """
            SELECT COUNT(*)
            FROM challengesphere.auth_tokens
            WHERE token_hash = ?
              AND token_type = ?
              AND user_id = ?
              AND expires_at > CURRENT_TIMESTAMP
            """;
    private static final String REVOKE_SQL = """
            DELETE FROM challengesphere.auth_tokens
            WHERE token_hash = ?
              AND token_type = ?
            """;
    private static final String FIND_USER_ID_SQL = """
            SELECT user_id
            FROM challengesphere.auth_tokens
            WHERE token_hash = ?
              AND token_type = ?
              AND expires_at > CURRENT_TIMESTAMP
            LIMIT 1
            """;
    private static final String REVOKE_ALL_SQL = """
            DELETE FROM challengesphere.auth_tokens
            WHERE user_id = ?
            """;
    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM challengesphere.auth_tokens
            WHERE expires_at <= CURRENT_TIMESTAMP
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuthTokenStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void store(String token, String type, Long userId, Duration ttl) {
        String tokenHash = tokenHash(token);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(ttl));
        try {
            jdbcTemplate.update(INSERT_TOKEN_SQL, tokenHash, type, userId, expiresAt);
        } catch (DataAccessException e) {
            logger.error("SQL token store failed for userId={}", userId, e);
            throw e;
        }
        deleteExpiredTokens();
    }

    public boolean exists(String token, String type, Long userId) {
        try {
            Integer count = jdbcTemplate.queryForObject(EXISTS_SQL, Integer.class, tokenHash(token), type, userId);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            logger.error("SQL token lookup failed for userId={}", userId, e);
            return false;
        }
    }

    public void revoke(String token, String type) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(REVOKE_SQL, tokenHash(token), type);
        } catch (DataAccessException e) {
            logger.warn("SQL token revoke failed for type={}: {}", type, e.getMessage());
        }
    }

    public Optional<Long> findUserId(String token, String type) {
        try {
            return jdbcTemplate.query(
                    FIND_USER_ID_SQL,
                    resultSet -> resultSet.next()
                            ? Optional.of(resultSet.getLong("user_id"))
                            : Optional.empty(),
                    tokenHash(token),
                    type
            );
        } catch (DataAccessException e) {
            logger.error("SQL token lookup failed for type={}", type, e);
            return Optional.empty();
        }
    }

    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            jdbcTemplate.update(REVOKE_ALL_SQL, userId);
        } catch (DataAccessException e) {
            logger.warn("SQL revoke-all failed for userId={}: {}", userId, e.getMessage());
        }
    }

    private String tokenHash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        return sha256(token);
    }

    private void deleteExpiredTokens() {
        try {
            jdbcTemplate.update(DELETE_EXPIRED_SQL);
        } catch (DataAccessException e) {
            logger.warn("Expired auth token cleanup failed: {}", e.getMessage());
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
