package com.ironside.challengesphere.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis Cache Service Implementation
 * Provides centralized caching operations for ChallengeSphere application
 *
 * Features:
 * - Type-safe caching with JSON serialization
 * - Rate limiting with sliding window
 * - Leaderboard management
 * - Session management
 * - Batch operations for performance
 * - Async operations to not block main flow
 */
@Service
public class RedisCacheServiceImpl {

    private static final Logger logger = LogManager.getLogger(RedisCacheServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("cacheExecutor")
    private Executor cacheExecutor;

    // Cache key prefixes for different entities
    private static final String CHALLENGE_PREFIX = "challenge:";
    private static final String USER_PREFIX = "user:";
    private static final String SUBMISSION_PREFIX = "submission:";
    private static final String LEADERBOARD_PREFIX = "leaderboard:";
    private static final String SESSION_PREFIX = "session:";
    private static final String RATE_LIMIT_PREFIX = "rate_limit:";
    private static final String RECENT_CHALLENGES_KEY = "recent:challenges";
    private static final String TRENDING_CHALLENGES_KEY = "trending:challenges";
    private static final String VERIFICATION_PREFIX = "verification:";

    // Default TTL values
    private static final Duration DEFAULT_CHALLENGE_TTL = Duration.ofHours(1);
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_SUBMISSION_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_USER_PROFILE_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_LEADERBOARD_TTL = Duration.ofDays(7);
    private static final Duration DEFAULT_VERIFICATION_TTL = Duration.ofMinutes(10);

    // ==================== HEALTH CHECK ====================

    /**
     * Check if Redis connection is healthy
     */
    public boolean isHealthy() {
        try {
            String pong = redisTemplate.execute(RedisConnectionCommands::ping);
            return "PONG".equals(pong);
        } catch (Exception e) {
            logger.error("Redis health check failed", e);
            return false;
        }
    }

    // ==================== BASIC CACHE OPERATIONS ====================

    /**
     * Save any object to cache with TTL
     * @param key The cache key
     * @param value The value to cache
     * @param ttl Duration for the cache entry to live
     * @return true if saved successfully, false otherwise
     */
    public <T> boolean save(String key, T value, Duration ttl) {
        if (key == null || value == null) {
            logger.warn("Cannot save null key or value to cache");
            return false;
        }
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, jsonValue, ttl);
            logger.debug("Saved to cache: key={}, ttl={}", key, ttl);
            return true;
        } catch (JsonProcessingException e) {
            logger.error("Error serializing value for cache: key={}", key, e);
            return false;
        } catch (Exception e) {
            logger.error("Error saving to cache: key={}", key, e);
            return false;
        }
    }

    /**
     * Save any object to cache with TTL (legacy method for backward compatibility)
     */
    public <T> void save(String key, T value, long timeout, TimeUnit unit) {
        save(key, value, Duration.of(timeout, unit.toChronoUnit()));
    }

    /**
     * Get value from cache with type-safe deserialization
     * @param key The cache key
     * @param type The class type to deserialize to
     * @return The cached value or null if not found
     */
    public <T> T get(String key, Class<T> type) {
        if (key == null) {
            return null;
        }
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                logger.debug("Cache miss: key={}", key);
                return null;
            }
            logger.debug("Cache hit: key={}", key);

            // Handle both JSON string values and direct object values
            if (value instanceof String stringValue) {
                return objectMapper.readValue(stringValue, type);
            }
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing value from cache: key={}", key, e);
            return null;
        } catch (Exception e) {
            logger.error("Error getting from cache: key={}", key, e);
            return null;
        }
    }

    /**
     * Get value from cache with Optional wrapper
     */
    public <T> Optional<T> getOptional(String key, Class<T> type) {
        return Optional.ofNullable(get(key, type));
    }

    /**
     * Delete key from cache
     * @return true if the key was deleted, false otherwise
     */
    public boolean delete(String key) {
        if (key == null) {
            return false;
        }
        try {
            boolean deleted = redisTemplate.delete(key);
            if (deleted) {
                logger.debug("Deleted from cache: key={}", key);
            }
            return deleted;
        } catch (Exception e) {
            logger.error("Error deleting from cache: key={}", key, e);
            return false;
        }
    }

    /**
     * Delete multiple keys from cache (batch operation)
     */
    public void deleteAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            Long deleted = redisTemplate.delete(keys);
            logger.debug("Batch deleted from cache: count={}", deleted);
        } catch (Exception e) {
            logger.error("Error batch deleting from cache", e);
        }
    }

    /**
     * Delete keys matching a pattern
     */
    public void deleteByPattern(String pattern) {
        if (pattern == null) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (!keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                logger.debug("Deleted keys matching pattern '{}': count={}", pattern, deleted);
            }
        } catch (Exception e) {
            logger.error("Error deleting by pattern: pattern={}", pattern, e);
        }
    }

    /**
     * Check if key exists
     */
    public boolean exists(String key) {
        if (key == null) {
            return false;
        }
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            logger.error("Error checking cache key: key={}", key, e);
            return false;
        }
    }

    /**
     * Set expiration on existing key
     */
    public boolean expire(String key, Duration ttl) {
        if (key == null || ttl == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.expire(key, ttl));
        } catch (Exception e) {
            logger.error("Error setting expiration: key={}", key, e);
            return false;
        }
    }

    /**
     * Get remaining TTL for a key
     * @return TTL in seconds, or -1 if key doesn't exist or has no expiry
     */
    public long getTtl(String key) {
        if (key == null) {
            return -1;
        }
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Error getting TTL: key={}", key, e);
            return -1;
        }
    }

    // ==================== ASYNC WRITE OPERATIONS ====================
    // All cache writes are async by default to not block the main flow
    // Read operations remain synchronous (caller needs the result)

    /**
     * Execute a cache operation asynchronously
     * Use this for all write operations (save, delete, invalidate)
     */
    private void executeAsync(Runnable task) {
        cacheExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.warn("Async cache operation failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Async save - fire and forget
     */
    public <T> void saveAsync(String key, T value, Duration ttl) {
        executeAsync(() -> save(key, value, ttl));
    }

    /**
     * Async delete - fire and forget
     */
    public void deleteAsync(String key) {
        executeAsync(() -> delete(key));
    }

    /**
     * Async delete by pattern - fire and forget
     */
    public void deleteByPatternAsync(String pattern) {
        executeAsync(() -> deleteByPattern(pattern));
    }

    /**
     * Async delete all - fire and forget
     */
    public void deleteAllAsync(Collection<String> keys) {
        executeAsync(() -> deleteAll(keys));
    }

    // ==================== USE CASE 1: CHALLENGE CACHING ====================

    /**
     * Cache individual challenge details (ASYNC - non-blocking)
     * Use Case: Reduce DB queries for frequently viewed challenges
     */
    public <T> void cacheChallenge(Long challengeId, T challenge) {
        if (challengeId == null || challenge == null) {
            return;
        }
        executeAsync(() -> {
            String key = CHALLENGE_PREFIX + challengeId;
            if (save(key, challenge, DEFAULT_CHALLENGE_TTL)) {
                logger.debug("Cached challenge: id={}", challengeId);
            }
        });
    }

    /**
     * Get challenge from cache (SYNC - needs result)
     */
    public <T> Optional<T> getCachedChallenge(Long challengeId, Class<T> type) {
        if (challengeId == null) {
            return Optional.empty();
        }
        String key = CHALLENGE_PREFIX + challengeId;
        return getOptional(key, type);
    }

    /**
     * Invalidate challenge cache (ASYNC - non-blocking)
     */
    public void invalidateChallenge(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        executeAsync(() -> {
            String key = CHALLENGE_PREFIX + challengeId;
            delete(key);
            logger.debug("Invalidated challenge cache: id={}", challengeId);
        });
    }

    // ==================== USE CASE 2: USER SESSION MANAGEMENT ====================

    /**
     * Store user session data (ASYNC - non-blocking)
     * Use Case: Fast session lookup without DB hits
     */
    public <T> void createSession(String sessionId, T sessionData) {
        if (sessionId == null || sessionData == null) {
            return;
        }
        executeAsync(() -> {
            String key = SESSION_PREFIX + sessionId;
            if (save(key, sessionData, DEFAULT_SESSION_TTL)) {
                logger.debug("Created session: sessionId={}", sessionId);
            }
        });
    }

    /**
     * Get session data (SYNC - needs result)
     */
    public <T> Optional<T> getSession(String sessionId, Class<T> type) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String key = SESSION_PREFIX + sessionId;
        return getOptional(key, type);
    }

    /**
     * Extend session timeout (ASYNC - non-blocking)
     */
    public void extendSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        executeAsync(() -> {
            String key = SESSION_PREFIX + sessionId;
            if (expire(key, DEFAULT_SESSION_TTL)) {
                logger.debug("Extended session: sessionId={}", sessionId);
            }
        });
    }

    /**
     * Invalidate session (ASYNC - non-blocking)
     */
    public void invalidateSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        executeAsync(() -> {
            String key = SESSION_PREFIX + sessionId;
            delete(key);
            logger.debug("Invalidated session: sessionId={}", sessionId);
        });
    }

    // ==================== USE CASE 3: RATE LIMITING ====================

    /**
     * Rate limit result containing limit status and metadata
     */
    public record RateLimitResult(boolean limited, long currentCount, long maxRequests, long windowSeconds) {
        public long remainingRequests() {
            return Math.max(0, maxRequests - currentCount);
        }
    }

    /**
     * Check if user exceeded rate limit using sliding window
     * Use Case: Prevent spam, protect APIs
     * @return RateLimitResult with detailed rate limit information
     */
    public RateLimitResult checkRateLimit(String identifier, int maxRequests, Duration window) {
        if (identifier == null) {
            return new RateLimitResult(false, 0, maxRequests, window.getSeconds());
        }

        String key = RATE_LIMIT_PREFIX + identifier;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return new RateLimitResult(false, 0, maxRequests, window.getSeconds());
            }

            if (count == 1) {
                // First request in window, set expiration
                redisTemplate.expire(key, window);
            }

            boolean limited = count > maxRequests;
            if (limited) {
                logger.warn("Rate limit exceeded: identifier={}, count={}, max={}",
                        identifier, count, maxRequests);
            }
            return new RateLimitResult(limited, count, maxRequests, window.getSeconds());

        } catch (Exception e) {
            logger.error("Error checking rate limit: identifier={}", identifier, e);
            return new RateLimitResult(false, 0, maxRequests, window.getSeconds()); // Fail open
        }
    }

    /**
     * Legacy method for backward compatibility
     */
    public boolean isRateLimited(String userId, int maxRequests, int windowMinutes) {
        return checkRateLimit(userId, maxRequests, Duration.ofMinutes(windowMinutes)).limited();
    }

    /**
     * Get current request count for user
     */
    public long getRateLimitCount(String userId) {
        if (userId == null) {
            return 0;
        }
        String key = RATE_LIMIT_PREFIX + userId;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value.toString()) : 0;
        } catch (Exception e) {
            logger.error("Error getting rate limit count: userId={}", userId, e);
            return 0;
        }
    }

    /**
     * Reset rate limit for a user (admin function)
     */
    public void resetRateLimit(String userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = RATE_LIMIT_PREFIX + userId;
            delete(key);
            logger.debug("Reset rate limit: userId={}", userId);
        });
    }

    // ==================== USE CASE 4: LEADERBOARD ====================

    /**
     * Leaderboard entry with user and score information
     */
    public record LeaderboardEntry(String userId, double score, long rank) {}

    /**
     * Add user score to leaderboard (ASYNC - non-blocking)
     * Use Case: Real-time leaderboard updates
     */
    public void addToLeaderboard(Long challengeId, Long userId, double score) {
        if (challengeId == null || userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = LEADERBOARD_PREFIX + challengeId;
            try {
                redisTemplate.opsForZSet().add(key, userId.toString(), score);
                expire(key, DEFAULT_LEADERBOARD_TTL);
                logger.debug("Added to leaderboard: challengeId={}, userId={}, score={}",
                        challengeId, userId, score);
            } catch (Exception e) {
                logger.warn("Error adding to leaderboard: challengeId={}, userId={}", challengeId, userId, e);
            }
        });
    }

    /**
     * Update user's score in leaderboard (ASYNC - non-blocking)
     */
    public void incrementLeaderboardScore(Long challengeId, Long userId, double delta) {
        if (challengeId == null || userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = LEADERBOARD_PREFIX + challengeId;
            try {
                Double newScore = redisTemplate.opsForZSet().incrementScore(key, userId.toString(), delta);
                logger.debug("Incremented leaderboard score: challengeId={}, userId={}, delta={}, newScore={}",
                        challengeId, userId, delta, newScore);
            } catch (Exception e) {
                logger.warn("Error incrementing leaderboard score", e);
            }
        });
    }

    /**
     * Get top N users from leaderboard with scores
     */
    public List<LeaderboardEntry> getTopScoresWithRank(Long challengeId, int count) {
        if (challengeId == null || count <= 0) {
            return List.of();
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Set<ZSetOperations.TypedTuple<Object>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, count - 1);

            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }

            List<LeaderboardEntry> entries = new ArrayList<>();
            long rank = 1;
            for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
                Object tupleValue = tuple.getValue();
                Double tupleScore = tuple.getScore();
                if (tupleValue != null && tupleScore != null) {
                    entries.add(new LeaderboardEntry(
                            tupleValue.toString(),
                            tupleScore,
                            rank++
                    ));
                }
            }
            return entries;
        } catch (Exception e) {
            logger.error("Error getting leaderboard: challengeId={}", challengeId, e);
            return List.of();
        }
    }

    /**
     * Get top N users from leaderboard (legacy method)
     */
    public Set<Object> getTopScores(Long challengeId, int count) {
        if (challengeId == null || count <= 0) {
            return Set.of();
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Set<Object> result = redisTemplate.opsForZSet().reverseRange(key, 0, count - 1);
            return result != null ? result : Set.of();
        } catch (Exception e) {
            logger.error("Error getting leaderboard: challengeId={}", challengeId, e);
            return Set.of();
        }
    }

    /**
     * Get user's rank in leaderboard (1-based)
     */
    public Optional<Long> getUserRank(Long challengeId, Long userId) {
        if (challengeId == null || userId == null) {
            return Optional.empty();
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
            return rank != null ? Optional.of(rank + 1) : Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting user rank: challengeId={}, userId={}", challengeId, userId, e);
            return Optional.empty();
        }
    }

    /**
     * Get user's score in leaderboard
     */
    public Optional<Double> getUserScore(Long challengeId, Long userId) {
        if (challengeId == null || userId == null) {
            return Optional.empty();
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Double score = redisTemplate.opsForZSet().score(key, userId.toString());
            return Optional.ofNullable(score);
        } catch (Exception e) {
            logger.error("Error getting user score: challengeId={}, userId={}", challengeId, userId, e);
            return Optional.empty();
        }
    }

    /**
     * Get leaderboard size
     */
    public long getLeaderboardSize(Long challengeId) {
        if (challengeId == null) {
            return 0;
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Long size = redisTemplate.opsForZSet().zCard(key);
            return size != null ? size : 0;
        } catch (Exception e) {
            logger.error("Error getting leaderboard size: challengeId={}", challengeId, e);
            return 0;
        }
    }

    /**
     * Remove user from leaderboard (ASYNC - non-blocking)
     */
    public void removeFromLeaderboard(Long challengeId, Long userId) {
        if (challengeId == null || userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = LEADERBOARD_PREFIX + challengeId;
            try {
                redisTemplate.opsForZSet().remove(key, userId.toString());
                logger.debug("Removed from leaderboard: challengeId={}, userId={}", challengeId, userId);
            } catch (Exception e) {
                logger.warn("Error removing from leaderboard: challengeId={}, userId={}", challengeId, userId, e);
            }
        });
    }

    // ==================== USE CASE 5: SUBMISSION CACHING ====================

    /**
     * Cache user's submission result (ASYNC - non-blocking)
     * Use Case: Quick access to recent submission results
     */
    public <T> void cacheSubmission(Long submissionId, T submission) {
        if (submissionId == null || submission == null) {
            return;
        }
        executeAsync(() -> {
            String key = SUBMISSION_PREFIX + submissionId;
            if (save(key, submission, DEFAULT_SUBMISSION_TTL)) {
                logger.debug("Cached submission: id={}", submissionId);
            }
        });
    }

    /**
     * Get cached submission (SYNC - needs result)
     */
    public <T> Optional<T> getCachedSubmission(Long submissionId, Class<T> type) {
        if (submissionId == null) {
            return Optional.empty();
        }
        String key = SUBMISSION_PREFIX + submissionId;
        return getOptional(key, type);
    }

    /**
     * Invalidate submission cache (ASYNC - non-blocking)
     */
    public void invalidateSubmission(Long submissionId) {
        if (submissionId == null) {
            return;
        }
        executeAsync(() -> {
            String key = SUBMISSION_PREFIX + submissionId;
            delete(key);
            logger.debug("Invalidated submission cache: id={}", submissionId);
        });
    }

    /**
     * Cache all submissions for a user-challenge pair (ASYNC - non-blocking)
     */
    public <T> void cacheUserSubmissions(Long userId, Long challengeId, T submissions) {
        if (userId == null || challengeId == null || submissions == null) {
            return;
        }
        executeAsync(() -> {
            String key = SUBMISSION_PREFIX + "user:" + userId + ":challenge:" + challengeId;
            save(key, submissions, Duration.ofMinutes(15));
        });
    }

    /**
     * Get cached submissions for a user-challenge pair (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserSubmissions(Long userId, Long challengeId, Class<T> type) {
        if (userId == null || challengeId == null) {
            return Optional.empty();
        }
        String key = SUBMISSION_PREFIX + "user:" + userId + ":challenge:" + challengeId;
        return getOptional(key, type);
    }

    /**
     * Invalidate user submissions cache for a challenge
     */
    public void invalidateUserSubmissions(Long userId, Long challengeId) {
        if (userId == null || challengeId == null) {
            return;
        }
        executeAsync(() -> {
            String key = SUBMISSION_PREFIX + "user:" + userId + ":challenge:" + challengeId;
            delete(key);
        });
    }

    // ==================== USE CASE 6: RECENT/TRENDING CHALLENGES ====================

    // Cache key patterns for paginated recent/trending
    private static final String RECENT_CHALLENGES_PAGE_PREFIX = "recent:challenges:page:";
    private static final String TRENDING_CHALLENGES_PAGE_PREFIX = "trending:challenges:page:";
    private static final String CHALLENGE_VIEWS_KEY = "challenge:views";
    private static final String CHALLENGE_ENGAGEMENT_KEY = "challenge:engagement";
    
    // TTL values for recent/trending
    private static final Duration RECENT_CHALLENGES_TTL = Duration.ofMinutes(5);
    private static final Duration TRENDING_CHALLENGES_TTL = Duration.ofMinutes(10);
    private static final Duration ENGAGEMENT_TTL = Duration.ofDays(7);

    /**
     * Cache list of recent challenges (ASYNC - non-blocking)
     * Use Case: Homepage/Dashboard performance
     */
    public <T> void cacheRecentChallenges(T challenges) {
        if (challenges == null) {
            return;
        }
        executeAsync(() -> {
            if (save(RECENT_CHALLENGES_KEY, challenges, RECENT_CHALLENGES_TTL)) {
                logger.debug("Cached recent challenges");
            }
        });
    }

    /**
     * Cache paginated recent challenges (ASYNC - non-blocking)
     */
    public <T> void cacheRecentChallengesPaginated(int page, int pageSize, T challenges) {
        if (challenges == null || page < 1) {
            return;
        }
        executeAsync(() -> {
            String key = RECENT_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
            if (save(key, challenges, RECENT_CHALLENGES_TTL)) {
                logger.debug("Cached recent challenges page {} (size={})", page, pageSize);
            }
        });
    }

    /**
     * Get recent challenges (SYNC - needs result)
     */
    public <T> Optional<T> getRecentChallenges(Class<T> type) {
        return getOptional(RECENT_CHALLENGES_KEY, type);
    }

    /**
     * Get paginated recent challenges from cache (SYNC - needs result)
     */
    public <T> Optional<T> getRecentChallengesPaginated(int page, int pageSize, Class<T> type) {
        if (page < 1) {
            return Optional.empty();
        }
        String key = RECENT_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate recent challenges cache (ASYNC - non-blocking)
     */
    public void invalidateRecentChallenges() {
        executeAsync(() -> {
            delete(RECENT_CHALLENGES_KEY);
            deleteByPattern(RECENT_CHALLENGES_PAGE_PREFIX + "*");
            logger.debug("Invalidated recent challenges cache (all pages)");
        });
    }

    /**
     * Cache trending challenges (ASYNC - non-blocking)
     */
    public <T> void cacheTrendingChallenges(T challenges) {
        if (challenges == null) {
            return;
        }
        executeAsync(() -> {
            if (save(TRENDING_CHALLENGES_KEY, challenges, TRENDING_CHALLENGES_TTL)) {
                logger.debug("Cached trending challenges");
            }
        });
    }

    /**
     * Cache paginated trending challenges (ASYNC - non-blocking)
     */
    public <T> void cacheTrendingChallengesPaginated(int page, int pageSize, T challenges) {
        if (challenges == null || page < 1) {
            return;
        }
        executeAsync(() -> {
            String key = TRENDING_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
            if (save(key, challenges, TRENDING_CHALLENGES_TTL)) {
                logger.debug("Cached trending challenges page {} (size={})", page, pageSize);
            }
        });
    }

    /**
     * Get trending challenges (SYNC - needs result)
     */
    public <T> Optional<T> getTrendingChallenges(Class<T> type) {
        return getOptional(TRENDING_CHALLENGES_KEY, type);
    }

    /**
     * Get paginated trending challenges from cache (SYNC - needs result)
     */
    public <T> Optional<T> getTrendingChallengesPaginated(int page, int pageSize, Class<T> type) {
        if (page < 1) {
            return Optional.empty();
        }
        String key = TRENDING_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate trending challenges cache (ASYNC - non-blocking)
     */
    public void invalidateTrendingChallenges() {
        executeAsync(() -> {
            delete(TRENDING_CHALLENGES_KEY);
            deleteByPattern(TRENDING_CHALLENGES_PAGE_PREFIX + "*");
            logger.debug("Invalidated trending challenges cache (all pages)");
        });
    }

    /**
     * Invalidate both recent and trending caches (ASYNC - non-blocking)
     * Use this when a challenge is created/updated/deleted
     */
    public void invalidateRecentAndTrendingChallenges() {
        executeAsync(() -> {
            delete(RECENT_CHALLENGES_KEY);
            deleteByPattern(RECENT_CHALLENGES_PAGE_PREFIX + "*");
            delete(TRENDING_CHALLENGES_KEY);
            deleteByPattern(TRENDING_CHALLENGES_PAGE_PREFIX + "*");
            logger.debug("Invalidated both recent and trending challenges caches");
        });
    }

    // ==================== CHALLENGE ENGAGEMENT TRACKING ====================

    /**
     * Increment challenge view count (ASYNC - non-blocking)
     * Use Case: Real-time trending calculation based on views
     */
    public void incrementChallengeViews(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        executeAsync(() -> {
            try {
                Double newScore = redisTemplate.opsForZSet()
                        .incrementScore(CHALLENGE_VIEWS_KEY, challengeId.toString(), 1);
                redisTemplate.expire(CHALLENGE_VIEWS_KEY, ENGAGEMENT_TTL);
                logger.debug("Incremented view count for challenge {}: new count={}", challengeId, newScore);
            } catch (Exception e) {
                logger.warn("Failed to increment challenge views: challengeId={}, error={}", challengeId, e.getMessage());
            }
        });
    }

    /**
     * Get challenge view count (SYNC - needs result)
     */
    public long getChallengeViews(Long challengeId) {
        if (challengeId == null) {
            return 0;
        }
        try {
            Double score = redisTemplate.opsForZSet().score(CHALLENGE_VIEWS_KEY, challengeId.toString());
            return score != null ? score.longValue() : 0;
        } catch (Exception e) {
            logger.warn("Failed to get challenge views: challengeId={}, error={}", challengeId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get top viewed challenges (SYNC - needs result)
     * @param limit Number of challenges to return
     * @return List of challenge IDs sorted by views (descending)
     */
    public List<Long> getTopViewedChallengeIds(int limit) {
        try {
            Set<ZSetOperations.TypedTuple<Object>> results = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(CHALLENGE_VIEWS_KEY, 0, limit - 1);
            
            if (results == null || results.isEmpty()) {
                return List.of();
            }
            
            return results.stream()
                    .map(tuple -> Long.parseLong(tuple.getValue().toString()))
                    .toList();
        } catch (Exception e) {
            logger.warn("Failed to get top viewed challenges: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Track challenge engagement (views + joins + submissions)
     * Higher score = more trending
     * @param challengeId The challenge ID
     * @param engagementPoints Points to add (e.g., view=1, join=5, submission=10)
     */
    public void trackChallengeEngagement(Long challengeId, int engagementPoints) {
        if (challengeId == null || engagementPoints <= 0) {
            return;
        }
        executeAsync(() -> {
            try {
                redisTemplate.opsForZSet()
                        .incrementScore(CHALLENGE_ENGAGEMENT_KEY, challengeId.toString(), engagementPoints);
                redisTemplate.expire(CHALLENGE_ENGAGEMENT_KEY, ENGAGEMENT_TTL);
                logger.debug("Tracked engagement for challenge {}: +{} points", challengeId, engagementPoints);
            } catch (Exception e) {
                logger.warn("Failed to track challenge engagement: challengeId={}, error={}", challengeId, e.getMessage());
            }
        });
    }

    /**
     * Get top engaged challenges (SYNC - needs result)
     * @param limit Number of challenges to return
     * @return List of challenge IDs sorted by engagement score (descending)
     */
    public List<Long> getTopEngagedChallengeIds(int limit) {
        try {
            Set<ZSetOperations.TypedTuple<Object>> results = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(CHALLENGE_ENGAGEMENT_KEY, 0, limit - 1);
            
            if (results == null || results.isEmpty()) {
                return List.of();
            }
            
            return results.stream()
                    .map(tuple -> Long.parseLong(tuple.getValue().toString()))
                    .toList();
        } catch (Exception e) {
            logger.warn("Failed to get top engaged challenges: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get engagement score for a specific challenge (SYNC - needs result)
     */
    public long getChallengeEngagementScore(Long challengeId) {
        if (challengeId == null) {
            return 0;
        }
        try {
            Double score = redisTemplate.opsForZSet().score(CHALLENGE_ENGAGEMENT_KEY, challengeId.toString());
            return score != null ? score.longValue() : 0;
        } catch (Exception e) {
            logger.warn("Failed to get challenge engagement score: challengeId={}, error={}", challengeId, e.getMessage());
            return 0;
        }
    }

    /**
     * Reset engagement tracking (ASYNC - non-blocking)
     */
    public void resetEngagementTracking() {
        executeAsync(() -> {
            try {
                delete(CHALLENGE_VIEWS_KEY);
                delete(CHALLENGE_ENGAGEMENT_KEY);
                logger.debug("Reset challenge engagement tracking");
            } catch (Exception e) {
                logger.warn("Failed to reset engagement tracking: error={}", e.getMessage());
            }
        });
    }

    // ==================== USE CASE 7: USER PROFILE CACHING ====================

    // Additional cache keys for user-related data
    private static final String USER_FOLLOWERS_PREFIX = "user:followers:";
    private static final String USER_FOLLOWING_PREFIX = "user:following:";
    private static final String USER_STATS_PREFIX = "user:stats:";
    private static final Duration USER_FOLLOWERS_TTL = Duration.ofMinutes(15);
    private static final Duration USER_STATS_TTL = Duration.ofMinutes(10);

    /**
     * Cache user profile (ASYNC - non-blocking)
     * Use Case: Reduce DB queries for user data
     */
    public <T> void cacheUserProfile(Long userId, T userProfile) {
        if (userId == null || userProfile == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_PREFIX + "profile:" + userId;
            if (save(key, userProfile, DEFAULT_USER_PROFILE_TTL)) {
                logger.debug("Cached user profile: userId={}", userId);
            }
        });
    }

    /**
     * Get cached user profile (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserProfile(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_PREFIX + "profile:" + userId;
        return getOptional(key, type);
    }

    /**
     * Invalidate user profile cache (ASYNC - non-blocking)
     */
    public void invalidateUserProfile(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_PREFIX + "profile:" + userId;
            delete(key);
            logger.debug("Invalidated user profile cache: userId={}", userId);
        });
    }

    /**
     * Cache user's followers list (ASYNC - non-blocking)
     */
    public <T> void cacheUserFollowers(Long userId, T followers) {
        if (userId == null || followers == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_FOLLOWERS_PREFIX + userId;
            if (save(key, followers, USER_FOLLOWERS_TTL)) {
                logger.debug("Cached followers for user: userId={}", userId);
            }
        });
    }

    /**
     * Cache user's followers list with pagination (ASYNC - non-blocking)
     */
    public <T> void cacheUserFollowers(Long userId, int page, int pageSize, T followers) {
        if (userId == null || followers == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_FOLLOWERS_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
            if (save(key, followers, USER_FOLLOWERS_TTL)) {
                logger.debug("Cached followers for user: userId={}, page={}, pageSize={}", userId, page, pageSize);
            }
        });
    }

    /**
     * Get cached user followers (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserFollowers(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWERS_PREFIX + userId;
        return getOptional(key, type);
    }

    /**
     * Get cached user followers with pagination (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserFollowers(Long userId, int page, int pageSize, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWERS_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate user followers cache (ASYNC - non-blocking)
     */
    public void invalidateUserFollowers(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_FOLLOWERS_PREFIX + userId;
            delete(key);
            deleteByPattern(USER_FOLLOWERS_PREFIX + userId + ":page:*");
            logger.debug("Invalidated followers cache: userId={}", userId);
        });
    }

    /**
     * Cache user's following list (ASYNC - non-blocking)
     */
    public <T> void cacheUserFollowing(Long userId, T following) {
        if (userId == null || following == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_FOLLOWING_PREFIX + userId;
            if (save(key, following, USER_FOLLOWERS_TTL)) {
                logger.debug("Cached following for user: userId={}", userId);
            }
        });
    }

    /**
     * Cache user's following list with pagination (ASYNC - non-blocking)
     */
    public <T> void cacheUserFollowing(Long userId, int page, int pageSize, T following) {
        if (userId == null || following == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_FOLLOWING_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
            if (save(key, following, USER_FOLLOWERS_TTL)) {
                logger.debug("Cached following for user: userId={}, page={}, pageSize={}", userId, page, pageSize);
            }
        });
    }

    /**
     * Get cached user following (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserFollowing(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWING_PREFIX + userId;
        return getOptional(key, type);
    }

    /**
     * Get cached user following with pagination (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserFollowing(Long userId, int page, int pageSize, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWING_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate user following cache (ASYNC - non-blocking)
     */
    public void invalidateUserFollowing(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_FOLLOWING_PREFIX + userId;
            delete(key);
            deleteByPattern(USER_FOLLOWING_PREFIX + userId + ":page:*");
            logger.debug("Invalidated following cache: userId={}", userId);
        });
    }

    /**
     * Cache user stats (ASYNC - non-blocking)
     */
    public <T> void cacheUserStats(Long userId, T stats) {
        if (userId == null || stats == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_STATS_PREFIX + userId;
            if (save(key, stats, USER_STATS_TTL)) {
                logger.debug("Cached stats for user: userId={}", userId);
            }
        });
    }

    /**
     * Get cached user stats (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserStats(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_STATS_PREFIX + userId;
        return getOptional(key, type);
    }

    /**
     * Invalidate user stats cache (ASYNC - non-blocking)
     */
    public void invalidateUserStats(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_STATS_PREFIX + userId;
            delete(key);
            logger.debug("Invalidated stats cache: userId={}", userId);
        });
    }

    /**
     * Invalidate all user-related caches (ASYNC - non-blocking)
     * Use when user is updated or deleted
     */
    public void invalidateAllUserCaches(Long userId) {
        if (userId == null) {
            return;
        }
        // All these are already async internally
        invalidateUserProfile(userId);
        invalidateUserFollowers(userId);
        invalidateUserFollowing(userId);
        invalidateUserStats(userId);
    }

    /**
     * Invalidate follow-related caches (ASYNC - non-blocking)
     * Use when follow/unfollow action occurs
     */
    public void invalidateFollowCaches(Long followerId, Long followingId) {
        // All these are already async internally
        invalidateUserFollowing(followerId);
        invalidateUserStats(followerId);
        invalidateUserFollowers(followingId);
        invalidateUserStats(followingId);
    }

    /**
     * Batch cache multiple user profiles (ASYNC - non-blocking)
     */
    public <T> void cacheUserProfiles(Map<Long, T> userProfiles) {
        if (userProfiles == null || userProfiles.isEmpty()) {
            return;
        }
        userProfiles.forEach(this::cacheUserProfile);
        logger.debug("Batch cached {} user profiles", userProfiles.size());
    }

    /**
     * Batch get multiple user profiles from cache
     * @return Map of userId to profile (only includes cache hits)
     */
    public <T> Map<Long, T> getCachedUserProfiles(List<Long> userIds, Class<T> type) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        
        Map<Long, T> result = new HashMap<>();
        for (Long userId : userIds) {
            getCachedUserProfile(userId, type).ifPresent(profile -> result.put(userId, profile));
        }
        logger.debug("Batch retrieved {} of {} user profiles from cache", result.size(), userIds.size());
        return result;
    }

    // ==================== USE CASE 8: TEMPORARY DATA STORAGE ====================

    /**
     * Store temporary verification codes (ASYNC - non-blocking)
     * Use Case: OTP, verification codes
     */
    public void storeVerificationCode(String identifier, String code) {
        if (identifier == null || code == null) {
            return;
        }
        executeAsync(() -> {
            String key = VERIFICATION_PREFIX + identifier;
            if (save(key, code, DEFAULT_VERIFICATION_TTL)) {
                logger.debug("Stored verification code: identifier={}", identifier);
            }
        });
    }

    /**
     * Store verification code with custom TTL (ASYNC - non-blocking)
     */
    public void storeVerificationCode(String identifier, String code, Duration ttl) {
        if (identifier == null || code == null) {
            return;
        }
        executeAsync(() -> {
            String key = VERIFICATION_PREFIX + identifier;
            if (save(key, code, ttl)) {
                logger.debug("Stored verification code: identifier={}, ttl={}", identifier, ttl);
            }
        });
    }

    /**
     * Verify and consume code (SYNC - needs result for verification flow)
     * @return true if code matches and was consumed
     */
    public boolean verifyCode(String identifier, String code) {
        if (identifier == null || code == null) {
            return false;
        }
        String key = VERIFICATION_PREFIX + identifier;
        String storedCode = get(key, String.class);

        if (storedCode != null && storedCode.equals(code)) {
            delete(key); // Consume the code (sync delete is fine here)
            logger.debug("Verification successful: identifier={}", identifier);
            return true;
        }
        logger.debug("Verification failed: identifier={}", identifier);
        return false;
    }

    /**
     * Check if verification code exists (SYNC - needs result)
     */
    public boolean hasVerificationCode(String identifier) {
        if (identifier == null) {
            return false;
        }
        String key = VERIFICATION_PREFIX + identifier;
        return exists(key);
    }

    /**
     * Invalidate verification code (ASYNC - non-blocking)
     */
    public void invalidateVerificationCode(String identifier) {
        if (identifier == null) {
            return;
        }
        executeAsync(() -> {
            String key = VERIFICATION_PREFIX + identifier;
            delete(key);
            logger.debug("Invalidated verification code: identifier={}", identifier);
        });
    }

    // ==================== USE CASE 9: COUNTER OPERATIONS ====================

    /**
     * Increment a counter atomically
     * @return new counter value
     */
    public long increment(String key) {
        if (key == null) {
            return 0;
        }
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            return value != null ? value : 0;
        } catch (Exception e) {
            logger.error("Error incrementing counter: key={}", key, e);
            return 0;
        }
    }

    /**
     * Increment a counter by delta atomically
     * @return new counter value
     */
    public long incrementBy(String key, long delta) {
        if (key == null) {
            return 0;
        }
        try {
            Long value = redisTemplate.opsForValue().increment(key, delta);
            return value != null ? value : 0;
        } catch (Exception e) {
            logger.error("Error incrementing counter: key={}, delta={}", key, delta, e);
            return 0;
        }
    }

    /**
     * Decrement a counter atomically
     * @return new counter value
     */
    public long decrement(String key) {
        if (key == null) {
            return 0;
        }
        try {
            Long value = redisTemplate.opsForValue().decrement(key);
            return value != null ? value : 0;
        } catch (Exception e) {
            logger.error("Error decrementing counter: key={}", key, e);
            return 0;
        }
    }

    /**
     * Reset challenge view count (ASYNC - non-blocking)
     */
    public void resetChallengeViews(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        executeAsync(() -> {
            try {
                redisTemplate.opsForZSet().remove(CHALLENGE_VIEWS_KEY, challengeId.toString());
                logger.debug("Reset views for challenge: {}", challengeId);
            } catch (Exception e) {
                logger.warn("Failed to reset challenge views: challengeId={}, error={}", challengeId, e.getMessage());
            }
        });
    }

    // ==================== USE CASE 10: CACHE INVALIDATION ====================

    /**
     * Clear all cache entries for a specific challenge (ASYNC - non-blocking)
     */
    public void clearChallengeCache(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        executeAsync(() -> {
            List<String> keys = List.of(
                    CHALLENGE_PREFIX + challengeId,
                    LEADERBOARD_PREFIX + challengeId
            );
            deleteAll(keys);
            
            // Remove from sorted sets
            try {
                redisTemplate.opsForZSet().remove(CHALLENGE_VIEWS_KEY, challengeId.toString());
                redisTemplate.opsForZSet().remove(CHALLENGE_ENGAGEMENT_KEY, challengeId.toString());
            } catch (Exception e) {
                logger.warn("Failed to remove challenge from sorted sets: {}", e.getMessage());
            }
            
            logger.debug("Cleared all cache for challenge: id={}", challengeId);
        });
        
        // These are already async
        invalidateRecentAndTrendingChallenges();
    }

    /**
     * Clear all cache entries for a user (ASYNC - non-blocking)
     */
    public void clearUserCache(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            List<String> keys = List.of(
                    USER_PREFIX + "profile:" + userId,
                    USER_PREFIX + "submissions:" + userId
            );
            deleteAll(keys);
            deleteByPattern(SUBMISSION_PREFIX + "user:" + userId + ":*");
            logger.debug("Cleared all cache for user: id={}", userId);
        });
    }

    /**
     * Clear all caches (ASYNC - non-blocking, admin only)
     */
    public void clearAllCaches() {
        executeAsync(() -> {
            try {
                Set<String> keys = redisTemplate.keys("*");
                if (!keys.isEmpty()) {
                    redisTemplate.delete(keys);
                    logger.warn("Cleared ALL caches: count={}", keys.size());
                }
            } catch (Exception e) {
                logger.error("Error clearing all caches", e);
            }
        });
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Get cache statistics (SYNC - needs result for monitoring)
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            stats.put("healthy", isHealthy());

            // Count keys by prefix
            stats.put("challengeKeys", countKeysByPattern(CHALLENGE_PREFIX + "*"));
            stats.put("userKeys", countKeysByPattern(USER_PREFIX + "*"));
            stats.put("sessionKeys", countKeysByPattern(SESSION_PREFIX + "*"));
            stats.put("leaderboardKeys", countKeysByPattern(LEADERBOARD_PREFIX + "*"));
            stats.put("submissionKeys", countKeysByPattern(SUBMISSION_PREFIX + "*"));
            stats.put("rateLimitKeys", countKeysByPattern(RATE_LIMIT_PREFIX + "*"));

            logger.info("Cache stats retrieved: {}", stats);
        } catch (Exception e) {
            logger.error("Error getting cache stats", e);
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    /**
     * Count keys matching a pattern
     */
    private long countKeysByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            return keys.size();
        } catch (Exception e) {
            logger.error("Error counting keys: pattern={}", pattern, e);
            return 0;
        }
    }

    /**
     * Log cache statistics
     */
    public void logCacheStats() {
        Map<String, Object> stats = getCacheStats();
        logger.info("Cache Statistics: {}", stats);
    }

    // ==================== CACHE-ASIDE PATTERN HELPER ====================

    /**
     * Get from cache or compute and cache the value
     * Implements cache-aside pattern
     *
     * @param key The cache key
     * @param type The class type
     * @param ttl Time to live for cached value
     * @param loader Function to load value if not in cache
     * @return The cached or computed value
     */
    public <T> T getOrCompute(String key, Class<T> type, Duration ttl,
                              java.util.function.Supplier<T> loader) {
        // Try cache first
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }

        // Load from source
        T value = loader.get();
        if (value != null) {
            save(key, value, ttl);
        }
        return value;
    }
}
