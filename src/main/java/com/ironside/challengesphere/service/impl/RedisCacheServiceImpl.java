package com.ironside.challengesphere.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
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
 */
@Service
public class RedisCacheServiceImpl {

    private static final Logger logger = LogManager.getLogger(RedisCacheServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;

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

    // ==================== USE CASE 1: CHALLENGE CACHING ====================

    /**
     * Cache individual challenge details
     * Use Case: Reduce DB queries for frequently viewed challenges
     */
    public <T> boolean cacheChallenge(Long challengeId, T challenge) {
        if (challengeId == null || challenge == null) {
            return false;
        }
        String key = CHALLENGE_PREFIX + challengeId;
        boolean saved = save(key, challenge, DEFAULT_CHALLENGE_TTL);
        if (saved) {
            logger.info("Cached challenge: id={}", challengeId);
        }
        return saved;
    }

    /**
     * Get challenge from cache
     */
    public <T> Optional<T> getCachedChallenge(Long challengeId, Class<T> type) {
        if (challengeId == null) {
            return Optional.empty();
        }
        String key = CHALLENGE_PREFIX + challengeId;
        return getOptional(key, type);
    }

    /**
     * Invalidate challenge cache (when updated)
     */
    public void invalidateChallenge(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        String key = CHALLENGE_PREFIX + challengeId;
        delete(key);
        logger.info("Invalidated challenge cache: id={}", challengeId);
    }

    // ==================== USE CASE 2: USER SESSION MANAGEMENT ====================

    /**
     * Store user session data
     * Use Case: Fast session lookup without DB hits
     */
    public <T> boolean createSession(String sessionId, T sessionData) {
        if (sessionId == null || sessionData == null) {
            return false;
        }
        String key = SESSION_PREFIX + sessionId;
        boolean saved = save(key, sessionData, DEFAULT_SESSION_TTL);
        if (saved) {
            logger.info("Created session: sessionId={}", sessionId);
        }
        return saved;
    }

    /**
     * Get session data
     */
    public <T> Optional<T> getSession(String sessionId, Class<T> type) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String key = SESSION_PREFIX + sessionId;
        return getOptional(key, type);
    }

    /**
     * Extend session timeout
     * @return true if session was extended, false if session doesn't exist
     */
    public boolean extendSession(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        String key = SESSION_PREFIX + sessionId;
        boolean extended = expire(key, DEFAULT_SESSION_TTL);
        if (extended) {
            logger.debug("Extended session: sessionId={}", sessionId);
        }
        return extended;
    }

    /**
     * Invalidate session (logout)
     */
    public void invalidateSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String key = SESSION_PREFIX + sessionId;
        delete(key);
        logger.info("Invalidated session: sessionId={}", sessionId);
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
        String key = RATE_LIMIT_PREFIX + userId;
        delete(key);
        logger.info("Reset rate limit: userId={}", userId);
    }

    // ==================== USE CASE 4: LEADERBOARD ====================

    /**
     * Leaderboard entry with user and score information
     */
    public record LeaderboardEntry(String userId, double score, long rank) {}

    /**
     * Add user score to leaderboard
     * Use Case: Real-time leaderboard updates
     * @return true if score was added successfully
     */
    public boolean addToLeaderboard(Long challengeId, Long userId, double score) {
        if (challengeId == null || userId == null) {
            return false;
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Boolean added = redisTemplate.opsForZSet().add(key, userId.toString(), score);
            expire(key, DEFAULT_LEADERBOARD_TTL);
            logger.info("Added to leaderboard: challengeId={}, userId={}, score={}",
                    challengeId, userId, score);
            return Boolean.TRUE.equals(added);
        } catch (Exception e) {
            logger.error("Error adding to leaderboard: challengeId={}, userId={}", challengeId, userId, e);
            return false;
        }
    }

    /**
     * Update user's score in leaderboard (atomic increment)
     */
    public Double incrementLeaderboardScore(Long challengeId, Long userId, double delta) {
        if (challengeId == null || userId == null) {
            return null;
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Double newScore = redisTemplate.opsForZSet().incrementScore(key, userId.toString(), delta);
            logger.debug("Incremented leaderboard score: challengeId={}, userId={}, delta={}, newScore={}",
                    challengeId, userId, delta, newScore);
            return newScore;
        } catch (Exception e) {
            logger.error("Error incrementing leaderboard score", e);
            return null;
        }
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
     * Remove user from leaderboard
     */
    public boolean removeFromLeaderboard(Long challengeId, Long userId) {
        if (challengeId == null || userId == null) {
            return false;
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            Long removed = redisTemplate.opsForZSet().remove(key, userId.toString());
            return removed != null && removed > 0;
        } catch (Exception e) {
            logger.error("Error removing from leaderboard: challengeId={}, userId={}", challengeId, userId, e);
            return false;
        }
    }

    // ==================== USE CASE 5: SUBMISSION CACHING ====================

    /**
     * Cache user's submission result
     * Use Case: Quick access to recent submission results
     */
    public <T> boolean cacheSubmission(Long submissionId, T submission) {
        if (submissionId == null || submission == null) {
            return false;
        }
        String key = SUBMISSION_PREFIX + submissionId;
        boolean saved = save(key, submission, DEFAULT_SUBMISSION_TTL);
        if (saved) {
            logger.info("Cached submission: id={}", submissionId);
        }
        return saved;
    }

    /**
     * Get cached submission
     */
    public <T> Optional<T> getCachedSubmission(Long submissionId, Class<T> type) {
        if (submissionId == null) {
            return Optional.empty();
        }
        String key = SUBMISSION_PREFIX + submissionId;
        return getOptional(key, type);
    }

    /**
     * Invalidate submission cache
     */
    public void invalidateSubmission(Long submissionId) {
        if (submissionId == null) {
            return;
        }
        String key = SUBMISSION_PREFIX + submissionId;
        delete(key);
        logger.debug("Invalidated submission cache: id={}", submissionId);
    }

    /**
     * Cache all submissions for a user-challenge pair
     */
    public <T> boolean cacheUserSubmissions(Long userId, Long challengeId, T submissions) {
        if (userId == null || challengeId == null || submissions == null) {
            return false;
        }
        String key = SUBMISSION_PREFIX + "user:" + userId + ":challenge:" + challengeId;
        return save(key, submissions, Duration.ofMinutes(15));
    }

    /**
     * Get cached submissions for a user-challenge pair
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
        String key = SUBMISSION_PREFIX + "user:" + userId + ":challenge:" + challengeId;
        delete(key);
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
     * Cache list of recent challenges (default - no pagination)
     * Use Case: Homepage/Dashboard performance
     */
    public <T> boolean cacheRecentChallenges(T challenges) {
        if (challenges == null) {
            return false;
        }
        boolean saved = save(RECENT_CHALLENGES_KEY, challenges, RECENT_CHALLENGES_TTL);
        if (saved) {
            logger.info("Cached recent challenges");
        }
        return saved;
    }

    /**
     * Cache paginated recent challenges
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @param challenges The challenges response to cache
     */
    public <T> boolean cacheRecentChallengesPaginated(int page, int pageSize, T challenges) {
        if (challenges == null || page < 1) {
            return false;
        }
        String key = RECENT_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
        boolean saved = save(key, challenges, RECENT_CHALLENGES_TTL);
        if (saved) {
            logger.debug("Cached recent challenges page {} (size={})", page, pageSize);
        }
        return saved;
    }

    /**
     * Get recent challenges (default - no pagination)
     */
    public <T> Optional<T> getRecentChallenges(Class<T> type) {
        return getOptional(RECENT_CHALLENGES_KEY, type);
    }

    /**
     * Get paginated recent challenges from cache
     */
    public <T> Optional<T> getRecentChallengesPaginated(int page, int pageSize, Class<T> type) {
        if (page < 1) {
            return Optional.empty();
        }
        String key = RECENT_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate recent challenges cache (all pages)
     */
    public void invalidateRecentChallenges() {
        delete(RECENT_CHALLENGES_KEY);
        deleteByPattern(RECENT_CHALLENGES_PAGE_PREFIX + "*");
        logger.debug("Invalidated recent challenges cache (all pages)");
    }

    /**
     * Cache trending challenges (default - no pagination)
     */
    public <T> boolean cacheTrendingChallenges(T challenges) {
        if (challenges == null) {
            return false;
        }
        boolean saved = save(TRENDING_CHALLENGES_KEY, challenges, TRENDING_CHALLENGES_TTL);
        if (saved) {
            logger.info("Cached trending challenges");
        }
        return saved;
    }

    /**
     * Cache paginated trending challenges
     */
    public <T> boolean cacheTrendingChallengesPaginated(int page, int pageSize, T challenges) {
        if (challenges == null || page < 1) {
            return false;
        }
        String key = TRENDING_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
        boolean saved = save(key, challenges, TRENDING_CHALLENGES_TTL);
        if (saved) {
            logger.debug("Cached trending challenges page {} (size={})", page, pageSize);
        }
        return saved;
    }

    /**
     * Get trending challenges (default - no pagination)
     */
    public <T> Optional<T> getTrendingChallenges(Class<T> type) {
        return getOptional(TRENDING_CHALLENGES_KEY, type);
    }

    /**
     * Get paginated trending challenges from cache
     */
    public <T> Optional<T> getTrendingChallengesPaginated(int page, int pageSize, Class<T> type) {
        if (page < 1) {
            return Optional.empty();
        }
        String key = TRENDING_CHALLENGES_PAGE_PREFIX + page + ":" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate trending challenges cache (all pages)
     */
    public void invalidateTrendingChallenges() {
        delete(TRENDING_CHALLENGES_KEY);
        deleteByPattern(TRENDING_CHALLENGES_PAGE_PREFIX + "*");
        logger.debug("Invalidated trending challenges cache (all pages)");
    }

    /**
     * Invalidate both recent and trending caches
     * Use this when a challenge is created/updated/deleted
     */
    public void invalidateRecentAndTrendingChallenges() {
        invalidateRecentChallenges();
        invalidateTrendingChallenges();
        logger.info("Invalidated both recent and trending challenges caches");
    }

    // ==================== CHALLENGE ENGAGEMENT TRACKING ====================

    /**
     * Increment challenge view count using Redis sorted set
     * Use Case: Real-time trending calculation based on views
     * @return the new view count
     */
    public long incrementChallengeViews(Long challengeId) {
        if (challengeId == null) {
            return 0;
        }
        try {
            Double newScore = redisTemplate.opsForZSet()
                    .incrementScore(CHALLENGE_VIEWS_KEY, challengeId.toString(), 1);
            // Set expiry on first increment
            redisTemplate.expire(CHALLENGE_VIEWS_KEY, ENGAGEMENT_TTL);
            logger.debug("Incremented view count for challenge {}: new count={}", challengeId, newScore);
            return newScore != null ? newScore.longValue() : 0;
        } catch (Exception e) {
            logger.warn("Failed to increment challenge views: challengeId={}, error={}", challengeId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get challenge view count
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
     * Get top viewed challenges (trending by views)
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
        try {
            redisTemplate.opsForZSet()
                    .incrementScore(CHALLENGE_ENGAGEMENT_KEY, challengeId.toString(), engagementPoints);
            redisTemplate.expire(CHALLENGE_ENGAGEMENT_KEY, ENGAGEMENT_TTL);
            logger.debug("Tracked engagement for challenge {}: +{} points", challengeId, engagementPoints);
        } catch (Exception e) {
            logger.warn("Failed to track challenge engagement: challengeId={}, error={}", challengeId, e.getMessage());
        }
    }

    /**
     * Get top engaged challenges (trending by engagement score)
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
     * Get engagement score for a specific challenge
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
     * Reset engagement tracking (e.g., weekly reset for fresh trending)
     */
    public void resetEngagementTracking() {
        try {
            delete(CHALLENGE_VIEWS_KEY);
            delete(CHALLENGE_ENGAGEMENT_KEY);
            logger.info("Reset challenge engagement tracking");
        } catch (Exception e) {
            logger.warn("Failed to reset engagement tracking: error={}", e.getMessage());
        }
    }

    // ==================== USE CASE 7: USER PROFILE CACHING ====================

    // Additional cache keys for user-related data
    private static final String USER_FOLLOWERS_PREFIX = "user:followers:";
    private static final String USER_FOLLOWING_PREFIX = "user:following:";
    private static final String USER_STATS_PREFIX = "user:stats:";
    private static final Duration USER_FOLLOWERS_TTL = Duration.ofMinutes(15);
    private static final Duration USER_STATS_TTL = Duration.ofMinutes(10);

    /**
     * Cache user profile
     * Use Case: Reduce DB queries for user data
     */
    public <T> boolean cacheUserProfile(Long userId, T userProfile) {
        if (userId == null || userProfile == null) {
            return false;
        }
        String key = USER_PREFIX + "profile:" + userId;
        boolean saved = save(key, userProfile, DEFAULT_USER_PROFILE_TTL);
        if (saved) {
            logger.debug("Cached user profile: userId={}", userId);
        }
        return saved;
    }

    /**
     * Get cached user profile
     */
    public <T> Optional<T> getCachedUserProfile(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_PREFIX + "profile:" + userId;
        return getOptional(key, type);
    }

    /**
     * Invalidate user profile cache (when updated)
     */
    public void invalidateUserProfile(Long userId) {
        if (userId == null) {
            return;
        }
        String key = USER_PREFIX + "profile:" + userId;
        delete(key);
        logger.debug("Invalidated user profile cache: userId={}", userId);
    }

    /**
     * Cache user's followers list
     */
    public <T> boolean cacheUserFollowers(Long userId, T followers) {
        if (userId == null || followers == null) {
            return false;
        }
        String key = USER_FOLLOWERS_PREFIX + userId;
        boolean saved = save(key, followers, USER_FOLLOWERS_TTL);
        if (saved) {
            logger.debug("Cached followers for user: userId={}", userId);
        }
        return saved;
    }

    /**
     * Cache user's followers list with pagination
     */
    public <T> boolean cacheUserFollowers(Long userId, int page, int pageSize, T followers) {
        if (userId == null || followers == null) {
            return false;
        }
        String key = USER_FOLLOWERS_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
        boolean saved = save(key, followers, USER_FOLLOWERS_TTL);
        if (saved) {
            logger.debug("Cached followers for user: userId={}, page={}, pageSize={}", userId, page, pageSize);
        }
        return saved;
    }

    /**
     * Get cached user followers
     */
    public <T> Optional<T> getCachedUserFollowers(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWERS_PREFIX + userId;
        return getOptional(key, type);
    }

    /**
     * Get cached user followers with pagination
     */
    public <T> Optional<T> getCachedUserFollowers(Long userId, int page, int pageSize, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWERS_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate user followers cache
     */
    public void invalidateUserFollowers(Long userId) {
        if (userId == null) {
            return;
        }
        String key = USER_FOLLOWERS_PREFIX + userId;
        delete(key);
        // Also invalidate paginated caches using pattern delete
        deleteByPattern(USER_FOLLOWERS_PREFIX + userId + ":page:*");
        logger.debug("Invalidated followers cache: userId={}", userId);
    }

    /**
     * Cache user's following list
     */
    public <T> boolean cacheUserFollowing(Long userId, T following) {
        if (userId == null || following == null) {
            return false;
        }
        String key = USER_FOLLOWING_PREFIX + userId;
        boolean saved = save(key, following, USER_FOLLOWERS_TTL);
        if (saved) {
            logger.debug("Cached following for user: userId={}", userId);
        }
        return saved;
    }

    /**
     * Cache user's following list with pagination
     */
    public <T> boolean cacheUserFollowing(Long userId, int page, int pageSize, T following) {
        if (userId == null || following == null) {
            return false;
        }
        String key = USER_FOLLOWING_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
        boolean saved = save(key, following, USER_FOLLOWERS_TTL);
        if (saved) {
            logger.debug("Cached following for user: userId={}, page={}, pageSize={}", userId, page, pageSize);
        }
        return saved;
    }

    /**
     * Get cached user following
     */
    public <T> Optional<T> getCachedUserFollowing(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWING_PREFIX + userId;
        return getOptional(key, type);
    }

    /**
     * Get cached user following with pagination
     */
    public <T> Optional<T> getCachedUserFollowing(Long userId, int page, int pageSize, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_FOLLOWING_PREFIX + userId + ":page:" + page + ":size:" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate user following cache
     */
    public void invalidateUserFollowing(Long userId) {
        if (userId == null) {
            return;
        }
        String key = USER_FOLLOWING_PREFIX + userId;
        delete(key);
        // Also invalidate paginated caches using pattern delete
        deleteByPattern(USER_FOLLOWING_PREFIX + userId + ":page:*");
        logger.debug("Invalidated following cache: userId={}", userId);
    }

    /**
     * Cache user stats (followers count, following count, challenges count, etc.)
     */
    public <T> boolean cacheUserStats(Long userId, T stats) {
        if (userId == null || stats == null) {
            return false;
        }
        String key = USER_STATS_PREFIX + userId;
        boolean saved = save(key, stats, USER_STATS_TTL);
        if (saved) {
            logger.debug("Cached stats for user: userId={}", userId);
        }
        return saved;
    }

    /**
     * Get cached user stats
     */
    public <T> Optional<T> getCachedUserStats(Long userId, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_STATS_PREFIX + userId;
        return getOptional(key, type);
    }

    /**
     * Invalidate user stats cache
     */
    public void invalidateUserStats(Long userId) {
        if (userId == null) {
            return;
        }
        String key = USER_STATS_PREFIX + userId;
        delete(key);
        logger.debug("Invalidated stats cache: userId={}", userId);
    }

    /**
     * Invalidate all user-related caches (profile, followers, following, stats)
     * Use when user is updated or deleted
     */
    public void invalidateAllUserCaches(Long userId) {
        if (userId == null) {
            return;
        }
        invalidateUserProfile(userId);
        invalidateUserFollowers(userId);
        invalidateUserFollowing(userId);
        invalidateUserStats(userId);
        logger.info("Invalidated all caches for user: userId={}", userId);
    }

    /**
     * Invalidate follow-related caches for both follower and following users
     * Use when follow/unfollow action occurs
     */
    public void invalidateFollowCaches(Long followerId, Long followingId) {
        // Follower's following list changed
        invalidateUserFollowing(followerId);
        invalidateUserStats(followerId);
        
        // Following user's followers list changed
        invalidateUserFollowers(followingId);
        invalidateUserStats(followingId);
        
        logger.debug("Invalidated follow caches: followerId={}, followingId={}", followerId, followingId);
    }

    /**
     * Batch cache multiple user profiles
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
     * Store temporary verification codes (email/phone)
     * Use Case: OTP, verification codes
     */
    public boolean storeVerificationCode(String identifier, String code) {
        if (identifier == null || code == null) {
            return false;
        }
        String key = VERIFICATION_PREFIX + identifier;
        boolean saved = save(key, code, DEFAULT_VERIFICATION_TTL);
        if (saved) {
            logger.info("Stored verification code: identifier={}", identifier);
        }
        return saved;
    }

    /**
     * Store verification code with custom TTL
     */
    public boolean storeVerificationCode(String identifier, String code, Duration ttl) {
        if (identifier == null || code == null) {
            return false;
        }
        String key = VERIFICATION_PREFIX + identifier;
        boolean saved = save(key, code, ttl);
        if (saved) {
            logger.info("Stored verification code: identifier={}, ttl={}", identifier, ttl);
        }
        return saved;
    }

    /**
     * Verify and consume code (one-time use)
     * @return true if code matches and was consumed
     */
    public boolean verifyCode(String identifier, String code) {
        if (identifier == null || code == null) {
            return false;
        }
        String key = VERIFICATION_PREFIX + identifier;
        String storedCode = get(key, String.class);

        if (storedCode != null && storedCode.equals(code)) {
            delete(key); // Consume the code
            logger.info("Verification successful: identifier={}", identifier);
            return true;
        }
        logger.warn("Verification failed: identifier={}", identifier);
        return false;
    }

    /**
     * Check if verification code exists (without consuming)
     */
    public boolean hasVerificationCode(String identifier) {
        if (identifier == null) {
            return false;
        }
        String key = VERIFICATION_PREFIX + identifier;
        return exists(key);
    }

    /**
     * Invalidate verification code
     */
    public void invalidateVerificationCode(String identifier) {
        if (identifier == null) {
            return;
        }
        String key = VERIFICATION_PREFIX + identifier;
        delete(key);
        logger.debug("Invalidated verification code: identifier={}", identifier);
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
     * Reset challenge view count (legacy - uses simple key)
     */
    public void resetChallengeViews(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        // Remove from sorted set
        try {
            redisTemplate.opsForZSet().remove(CHALLENGE_VIEWS_KEY, challengeId.toString());
            logger.debug("Reset views for challenge: {}", challengeId);
        } catch (Exception e) {
            logger.warn("Failed to reset challenge views: challengeId={}, error={}", challengeId, e.getMessage());
        }
    }

    // ==================== USE CASE 10: CACHE INVALIDATION ====================

    /**
     * Clear all cache entries for a specific challenge
     */
    public void clearChallengeCache(Long challengeId) {
        if (challengeId == null) {
            return;
        }
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
        
        // Invalidate recent/trending caches since they might contain this challenge
        invalidateRecentAndTrendingChallenges();
        
        logger.info("Cleared all cache for challenge: id={}", challengeId);
    }

    /**
     * Clear all cache entries for a user
     */
    public void clearUserCache(Long userId) {
        if (userId == null) {
            return;
        }
        List<String> keys = List.of(
                USER_PREFIX + "profile:" + userId,
                USER_PREFIX + "submissions:" + userId
        );
        deleteAll(keys);

        // Also clear any user-specific submission caches
        deleteByPattern(SUBMISSION_PREFIX + "user:" + userId + ":*");
        logger.info("Cleared all cache for user: id={}", userId);
    }

    /**
     * Clear all caches (use with caution - admin only)
     */
    public void clearAllCaches() {
        try {
            Set<String> keys = redisTemplate.keys("*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.warn("Cleared ALL caches: count={}", keys.size());
            }
        } catch (Exception e) {
            logger.error("Error clearing all caches", e);
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Get cache statistics (useful for monitoring)
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
