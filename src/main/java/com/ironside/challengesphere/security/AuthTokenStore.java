package com.ironside.challengesphere.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthTokenStore {

    private static final Logger logger = LogManager.getLogger(AuthTokenStore.class);
    private static final String PREFIX = "auth:jwt:";
    private static final String USER_PREFIX = "auth:jwt:user:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, FallbackSession> fallbackSessions = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> fallbackUserKeys = new ConcurrentHashMap<>();

    public AuthTokenStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void store(String token, String type, Long userId, Duration ttl) {
        String key = key(token, type);
        Instant expiresAt = Instant.now().plus(ttl);
        fallbackSessions.put(key, new FallbackSession(userId, type, expiresAt));
        fallbackUserKeys.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(key);
        try {
            redisTemplate.opsForValue().set(key, userId.toString(), ttl);
            redisTemplate.opsForSet().add(userKey(userId), key);
            redisTemplate.expire(userKey(userId), ttl.plus(Duration.ofDays(1)));
        } catch (Exception e) {
            logger.warn("Redis token store failed for userId={}; using in-memory fallback: {}", userId, e.getMessage());
        }
    }

    public boolean exists(String token, String type, Long userId) {
        String key = key(token, type);
        try {
            Object cachedUserId = redisTemplate.opsForValue().get(key);
            if (cachedUserId != null) {
                return userId.toString().equals(cachedUserId.toString());
            }
        } catch (Exception e) {
            logger.warn("Redis token lookup failed; checking in-memory fallback: {}", e.getMessage());
        }

        FallbackSession session = fallbackSessions.get(key);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            fallbackSessions.remove(key);
            return false;
        }
        return session.userId().equals(userId) && session.type().equals(type);
    }

    public void revoke(String token, String type) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = key(token, type);
        FallbackSession removed = fallbackSessions.remove(key);
        if (removed != null) {
            Set<String> keys = fallbackUserKeys.get(removed.userId());
            if (keys != null) {
                keys.remove(key);
            }
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.warn("Redis token revoke failed for type={}: {}", type, e.getMessage());
        }
    }

    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }

        Set<String> fallbackKeys = fallbackUserKeys.remove(userId);
        if (fallbackKeys != null) {
            for (String key : fallbackKeys) {
                fallbackSessions.remove(key);
            }
        }

        String userKey = userKey(userId);
        try {
            Set<Object> redisKeys = redisTemplate.opsForSet().members(userKey);
            if (redisKeys != null && !redisKeys.isEmpty()) {
                redisTemplate.delete(redisKeys.stream().map(Object::toString).toList());
            }
            redisTemplate.delete(userKey);
        } catch (Exception e) {
            logger.warn("Redis revoke-all failed for userId={}: {}", userId, e.getMessage());
        }

        // Defensive cleanup for sessions created before user-key indexing existed.
        for (String key : new ArrayList<>(fallbackSessions.keySet())) {
            FallbackSession session = fallbackSessions.get(key);
            if (session != null && userId.equals(session.userId())) {
                fallbackSessions.remove(key);
            }
        }
    }

    private String key(String token, String type) {
        return PREFIX + type + ":" + sha256(token);
    }

    private String userKey(Long userId) {
        return USER_PREFIX + userId;
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

    private record FallbackSession(Long userId, String type, Instant expiresAt) {
    }
}
