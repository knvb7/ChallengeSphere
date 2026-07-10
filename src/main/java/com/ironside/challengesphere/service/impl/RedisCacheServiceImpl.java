package com.ironside.challengesphere.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.ironside.challengesphere.model.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.model.Challenge;

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

    private static final String CHALLENGE_PREFIX = "challenge:";
    private static final String USER_PREFIX = "user:";
    private static final String SUBMISSION_PREFIX = "submission:";
    private static final String LEADERBOARD_PREFIX = "leaderboard:";
    private static final String SESSION_PREFIX = "session:";
    private static final String RATE_LIMIT_PREFIX = "rate_limit:";
    private static final String RECENT_CHALLENGES_KEY = "recent:challenges";
    private static final String TRENDING_CHALLENGES_KEY = "trending:challenges";
    private static final String VERIFICATION_PREFIX = "verification:";
    private static final String USER_CHALLENGES_PREFIX = "user:challenges:";
    private static final String CHALLENGE_PARTICIPANTS_PREFIX = "challenge:participants:";
    private static final String HASHTAG_CHALLENGES_PREFIX = "challenge:hashtag:";
    private static final String COMMENTS_PREFIX = "comments:submission:";
    private static final String GLORY_COUNT_PREFIX = "glory:count:";
    private static final String GLORY_CHECK_PREFIX = "glory:check:";
    private static final String FEED_PREFIX = "feed:";
    private static final String FEED_TIMELINE_PREFIX = "feed:timeline:";
    private static final String DIRTY_LOGIN_SET_KEY = "dirty:last_login";
    private static final Duration DEFAULT_CHALLENGE_TTL = Duration.ofDays(7);
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_SUBMISSION_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_USER_PROFILE_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_USER_EMAILANDUSERNAME_PROFILE_TTL = Duration.ofDays(7);
    private static final Duration DEFAULT_LEADERBOARD_TTL = Duration.ofDays(7);
    private static final Duration DEFAULT_VERIFICATION_TTL = Duration.ofMinutes(10);
    private static final Duration USER_CHALLENGES_TTL = Duration.ofMinutes(15);
    private static final Duration CHALLENGE_PARTICIPANTS_TTL = Duration.ofMinutes(10);
    private static final Duration HASHTAG_CHALLENGES_TTL = Duration.ofMinutes(10);
    private static final Duration COMMENTS_TTL = Duration.ofMinutes(10);
    private static final Duration GLORY_TTL = Duration.ofMinutes(15);
    private static final Duration FEED_TTL = Duration.ofMinutes(5);
    private static final Duration FEED_TIMELINE_TTL = Duration.ofHours(1);
    private static final Duration REDIS_FAILURE_COOLDOWN = Duration.ofMinutes(1);
    private static final int FEED_TIMELINE_MAX_SIZE = 500;
    private final AtomicLong redisRetryAfterMillis = new AtomicLong();

    // ==================== HEALTH CHECK ====================

    /**
     * Check if Redis connection is healthy
     */
    public boolean isHealthy() {
        try {
            String pong = redisTemplate.execute(RedisConnectionCommands::ping);
            boolean healthy = "PONG".equals(pong);
            if (healthy) {
                redisRetryAfterMillis.set(0);
            }
            return healthy;
        } catch (Exception e) {
            markRedisUnavailable(e);
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
        if (shouldBypassRedis()) {
            return false;
        }
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, jsonValue, ttl);
            logger.debug("Saved value to cache with ttl={}", ttl);
            return true;
        } catch (JsonProcessingException e) {
            logger.error("Error serializing value for cache");
            return false;
        } catch (Exception e) {
            markRedisUnavailable(e);
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
        if (shouldBypassRedis()) {
            return null;
        }
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                logger.debug("Cache miss");
                return null;
            }
            logger.debug("Cache hit");

            // Handle both JSON string values and direct object values
            if (value instanceof String stringValue) {
                return objectMapper.readValue(stringValue, type);
            }
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing value from cache");
            return null;
        } catch (Exception e) {
            markRedisUnavailable(e);
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
        if (shouldBypassRedis()) {
            return false;
        }
        try {
            boolean deleted = redisTemplate.delete(key);
            if (deleted) {
                logger.debug("Deleted value from cache");
            }
            return deleted;
        } catch (Exception e) {
            markRedisUnavailable(e);
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
        if (shouldBypassRedis()) {
            return;
        }
        try {
            Long deleted = redisTemplate.delete(keys);
            logger.debug("Batch deleted from cache: count={}", deleted);
        } catch (Exception e) {
            markRedisUnavailable(e);
        }
    }

    /**
     * Delete keys matching a pattern
     */
    public void deleteByPattern(String pattern) {
        if (pattern == null) {
            return;
        }
        if (shouldBypassRedis()) {
            return;
        }
        try {
            Set<String> keys = scanKeys(pattern);
            if (!keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                logger.debug("Deleted cache entries by pattern; count={}", deleted);
            }
        } catch (Exception e) {
            markRedisUnavailable(e);
        }
    }

    /**
     * Check if key exists
     */
    public boolean exists(String key) {
        if (key == null) {
            return false;
        }
        if (shouldBypassRedis()) {
            return false;
        }
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            markRedisUnavailable(e);
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
        if (shouldBypassRedis()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.expire(key, ttl));
        } catch (Exception e) {
            markRedisUnavailable(e);
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
        if (shouldBypassRedis()) {
            return -1;
        }
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            markRedisUnavailable(e);
            return -1;
        }
    }

    private boolean shouldBypassRedis() {
        return System.currentTimeMillis() < redisRetryAfterMillis.get();
    }

    private Set<String> scanKeys(String pattern) {
        if (pattern == null || shouldBypassRedis()) {
            return Set.of();
        }
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            Set<String> keys = new HashSet<>();
            cursor.forEachRemaining(keys::add);
            return keys;
        } catch (Exception e) {
            markRedisUnavailable(e);
            return Set.of();
        }
    }

    private void markRedisUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long retryAfter = now + REDIS_FAILURE_COOLDOWN.toMillis();
        long previousRetryAfter = redisRetryAfterMillis.getAndUpdate(previous -> Math.max(previous, retryAfter));
        if (previousRetryAfter <= now) {
            logger.warn("Redis unavailable; bypassing cache for {} seconds: {}",
                    REDIS_FAILURE_COOLDOWN.toSeconds(), exception.getClass().getSimpleName());
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
                logger.warn("Async cache operation failed: {}", e.getClass().getSimpleName());
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

    // ==================== USE CASE 4: LEADERBOARD ====================

    /**
     * Leaderboard entry with user and score information
     */
    public record LeaderboardEntry(String userId, double score, long rank) {}

    /**
     * Add user score to leaderboard.
     * Use Case: Real-time leaderboard updates where DB and Redis should be updated
     * before the caller returns.
     */
    public void addToLeaderboard(Long challengeId, Long userId, double score) {
        if (challengeId == null || userId == null) {
            return;
        }
        String key = LEADERBOARD_PREFIX + challengeId;
        try {
            redisTemplate.opsForZSet().add(key, userId.toString(), score);
            expire(key, DEFAULT_LEADERBOARD_TTL);
            logger.debug("Added to leaderboard: challengeId={}, userId={}, score={}",
                    challengeId, userId, score);
        } catch (Exception e) {
            markRedisUnavailable(e);
            logger.warn("Error adding to leaderboard: challengeId={}, userId={}", challengeId, userId);
        }
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
                logger.warn("Error incrementing leaderboard score");
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
            logger.error("Error getting leaderboard: challengeId={}", challengeId);
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
            logger.error("Error getting leaderboard: challengeId={}", challengeId);
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
            logger.error("Error getting user rank: challengeId={}, userId={}", challengeId, userId);
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
            logger.error("Error getting user score: challengeId={}, userId={}", challengeId, userId);
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
            logger.error("Error getting leaderboard size: challengeId={}", challengeId);
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
                logger.warn("Error removing from leaderboard: challengeId={}, userId={}", challengeId, userId);
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
    private static final String CHALLENGE_VIEWS_KEY = "challenge:views";
    private static final String CHALLENGE_ENGAGEMENT_KEY = "challenge:engagement";
    
    // TTL values for recent/trending
    private static final Duration RECENT_CHALLENGES_TTL = Duration.ofMinutes(5);
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
     * Invalidate both recent and trending caches (ASYNC - non-blocking)
     * Use this when a challenge is created/updated/deleted
     */
    public void invalidateRecentAndTrendingChallenges() {
        executeAsync(() -> {
            delete(RECENT_CHALLENGES_KEY);
            deleteByPattern(RECENT_CHALLENGES_PAGE_PREFIX + "*");
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
                logger.warn("Failed to increment challenge views: challengeId={}, error={}", challengeId, e.getClass().getSimpleName());
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
            logger.warn("Failed to get challenge views: challengeId={}, error={}", challengeId, e.getClass().getSimpleName());
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
            logger.warn("Failed to get top viewed challenges: error={}", e.getClass().getSimpleName());
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
                logger.warn("Failed to track challenge engagement: challengeId={}, error={}", challengeId, e.getClass().getSimpleName());
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
            logger.warn("Failed to get top engaged challenges: error={}", e.getClass().getSimpleName());
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
            logger.warn("Failed to get challenge engagement score: challengeId={}, error={}", challengeId, e.getClass().getSimpleName());
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
                logger.warn("Failed to reset engagement tracking: error={}", e.getClass().getSimpleName());
            }
        });
    }

    // ==================== USE CASE 7: USER PROFILE CACHING ====================

    // Additional cache keys for user-related data
    private static final String USER_STATS_PREFIX = "user:stats:";
    private static final Duration USER_STATS_TTL = Duration.ofMinutes(10);

    /**
     * Cache user profile (ASYNC - non-blocking)
     * Use Case: Reduce DB queries for user data
     */
    public <T> void cacheUserProfile(T userProfile) {
        if (userProfile == null)
            return;
        executeAsync(() -> {
            String emailId = ((User)userProfile).getEmailId();
            String username = ((User)userProfile).getUsername();
            Long userId = ((User)userProfile).getId();

            String keyUserId = USER_PREFIX + "profile:" + userId;
            if (save(keyUserId, userProfile, DEFAULT_USER_PROFILE_TTL)) {
                logger.debug("Cached user profile: userId={}", userId);
            }
            String keyEmailId = USER_PREFIX + "emailId:" + emailId;
            if (save(keyEmailId, userId, DEFAULT_USER_EMAILANDUSERNAME_PROFILE_TTL)) {
                logger.debug("Cached user profile email lookup for userId={}", userId);
            }
            String keyUsername = USER_PREFIX + "username:" + username;
            if (save(keyUsername, userId, DEFAULT_USER_EMAILANDUSERNAME_PROFILE_TTL)) {
                logger.debug("Cached user profile username lookup for userId={}", userId);
            }
        });
    }

    /**
     * Get cached user profile (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserProfile(Long userId, String email, String username, Class<T> type) {

        int provided = 0;
        if (userId != null) provided++;
        if (email != null && !email.isBlank()) provided++;
        if (username != null && !username.isBlank()) provided++;

        if (provided == 0)
            throw new IllegalArgumentException("At least one of userId, email, or username must be provided");

        Long resolvedUserId = userId;
        if (resolvedUserId == null && email != null && !email.isBlank()) {
            String emailKey = USER_PREFIX + "emailId:" + email.toLowerCase();
            Optional<Long> userIdOptional =
                    getOptional(emailKey, Long.class);

            if (userIdOptional.isEmpty())
                return Optional.empty();
            resolvedUserId = userIdOptional.get();
        }

        if (resolvedUserId == null && username != null && !username.isBlank()) {
            String usernameKey = USER_PREFIX + "username:" + username.toLowerCase();
            Optional<Long> userIdOptional =
                    getOptional(usernameKey, Long.class);

            if (userIdOptional.isEmpty())
                return Optional.empty();
            resolvedUserId = userIdOptional.get();
        }
        String profileKey = USER_PREFIX + "profile:" + resolvedUserId;
        return getOptional(profileKey, type);
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

    // ==================== FOLLOW RELATIONSHIP SORTED SETS ====================
    //
    // Instead of caching serialized paginated JSON blobs and invalidating on every
    // follow/unfollow, we model the relationship directly in Redis Sorted Sets:
    //   follows:following:{userId} → members = followed user IDs, scores = timestamps
    //   follows:followers:{userId} → members = follower user IDs, scores = timestamps
    //
    // On follow:  ZADD to both sets — O(log N), zero invalidation
    // On unfollow: ZREM from both sets — O(log N), zero invalidation
    // On read page: ZREVRANGE with offset+limit — O(log N + M)
    // Total count: ZCARD — O(1)

    private static final String FOLLOW_FOLLOWING_PREFIX = "follows:following:";
    private static final String FOLLOW_FOLLOWERS_PREFIX = "follows:followers:";

    /**
     * Record a follow relationship in both sorted sets.
     * Called after DB persist — fire-and-forget.
     */
    public void addFollow(Long followerId, Long followingId, long timestampMillis) {
        if (followerId == null || followingId == null) return;
        executeAsync(() -> {
            try {
                double score = timestampMillis;
                redisTemplate.opsForZSet().add(
                        FOLLOW_FOLLOWING_PREFIX + followerId, followingId.toString(), score);
                redisTemplate.opsForZSet().add(
                        FOLLOW_FOLLOWERS_PREFIX + followingId, followerId.toString(), score);
                logger.debug("ZADD follow: {} → {}", followerId, followingId);
            } catch (Exception e) {
                logger.warn("Failed to ZADD follow {}->{}: {}", followerId, followingId, e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Remove a follow relationship from both sorted sets.
     * Called after DB soft-delete — fire-and-forget.
     */
    public void removeFollow(Long followerId, Long followingId) {
        if (followerId == null || followingId == null) return;
        executeAsync(() -> {
            try {
                redisTemplate.opsForZSet().remove(
                        FOLLOW_FOLLOWING_PREFIX + followerId, followingId.toString());
                redisTemplate.opsForZSet().remove(
                        FOLLOW_FOLLOWERS_PREFIX + followingId, followerId.toString());
                logger.debug("ZREM follow: {} → {}", followerId, followingId);
            } catch (Exception e) {
                logger.warn("Failed to ZREM follow {}->{}: {}", followerId, followingId, e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Get a page of user IDs from a follow sorted set (newest first).
     *
     * @param key    the sorted set key (e.g. follows:followers:5)
     * @param offset zero-based offset (page-1)*pageSize
     * @param count  page size
     * @return list of user IDs, or empty list on miss/error
     */
    public List<Long> getFollowPage(String key, long offset, long count) {
        try {
            Set<Object> members = redisTemplate.opsForZSet()
                    .reverseRange(key, offset, offset + count - 1);
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            return members.stream()
                    .map(m -> Long.parseLong(m.toString()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Failed to read cached sorted set: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Total member count of a follow sorted set — O(1).
     *
     * @return count, or -1 if the key does not exist / error
     */
    public long getFollowCount(String key) {
        try {
            Long size = redisTemplate.opsForZSet().zCard(key);
            return size != null ? size : -1;
        } catch (Exception e) {
            logger.warn("Failed to count cached sorted set: {}", e.getClass().getSimpleName());
            return -1;
        }
    }

    /**
     * Check whether a sorted set key exists (used to detect cold-start).
     */
    public boolean followSetExists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Bulk-load a list of follow relationships into a sorted set.
     * Used for cold-start back-fill.
     */
    public void backfillFollowSet(String key, List<Long> userIds, List<Long> timestamps) {
        if (userIds == null || userIds.isEmpty()) return;
        executeAsync(() -> {
            try {
                Set<ZSetOperations.TypedTuple<Object>> tuples = new java.util.LinkedHashSet<>();
                for (int i = 0; i < userIds.size(); i++) {
                    double score = (i < timestamps.size()) ? timestamps.get(i) : 0;
                    tuples.add(ZSetOperations.TypedTuple.of(userIds.get(i).toString(), score));
                }
                redisTemplate.opsForZSet().add(key, tuples);
                logger.debug("Backfilled {} cached members", tuples.size());
            } catch (Exception e) {
                logger.warn("Failed to backfill cache: {}", e.getClass().getSimpleName());
            }
        });
    }

    public String getFollowingKey(Long userId) {
        return FOLLOW_FOLLOWING_PREFIX + userId;
    }

    public String getFollowersKey(Long userId) {
        return FOLLOW_FOLLOWERS_PREFIX + userId;
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
     * Use when user is updated or deleted.
     * Note: follow sorted sets are long-lived and NOT invalidated here —
     *       they are mutated in-place via addFollow/removeFollow.
     */
    public void invalidateAllUserCaches(Long userId) {
        if (userId == null) {
            return;
        }
        invalidateUserProfile(userId);
        invalidateUserStats(userId);
    }


    /**
     * Batch cache multiple user profiles (ASYNC - non-blocking)
     */
    public <T> void cacheUserProfiles(Map<Long, T> userProfiles) {
        if (userProfiles == null || userProfiles.isEmpty()) {
            return;
        }
        for(Map.Entry<Long, T> userProfile : userProfiles.entrySet())
            cacheUserProfile(userProfile.getValue());
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
            getCachedUserProfile(userId, null, null, type).ifPresent(profile -> result.put(userId, profile));
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
                logger.debug("Stored verification code");
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
                logger.debug("Stored verification code with ttl={}", ttl);
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
            logger.debug("Verification code consumed successfully");
            return true;
        }
        logger.debug("Verification code check failed");
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
            logger.debug("Invalidated verification code");
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
            logger.error("Error incrementing cache counter");
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
            logger.error("Error incrementing cache counter by delta={}", delta);
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
            logger.error("Error decrementing cache counter");
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
                logger.warn("Failed to reset challenge views: challengeId={}, error={}", challengeId, e.getClass().getSimpleName());
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
                logger.warn("Failed to remove challenge from sorted sets: {}", e.getClass().getSimpleName());
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
                Set<String> keys = scanKeys("*");
                if (!keys.isEmpty()) {
                    redisTemplate.delete(keys);
                    logger.warn("Cleared ALL caches: count={}", keys.size());
                }
            } catch (Exception e) {
                logger.error("Error clearing all caches");
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
            logger.error("Error getting cache stats");
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    /**
     * Count keys matching a pattern
     */
    private long countKeysByPattern(String pattern) {
        try {
            Set<String> keys = scanKeys(pattern);
            return keys.size();
        } catch (Exception e) {
            logger.error("Error counting cache entries");
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

    // ==================== USE CASE 11: USER CHALLENGES CACHING ====================

    /**
     * Cache user's created/owned challenges response (ASYNC - non-blocking)
     * Use Case: Quick access to user's challenges list on profile/dashboard
     */
    public <T> void cacheUserChallenges(Long userId, int page, int pageSize, Boolean active, T response) {
        if (userId == null || response == null) {
            return;
        }
        executeAsync(() -> {
            String key = USER_CHALLENGES_PREFIX + userId + ":page:" + page + ":size:" + pageSize
                    + ":active:" + (active != null ? active : "all");
            if (save(key, response, USER_CHALLENGES_TTL)) {
                logger.debug("Cached user challenges: userId={}, page={}, pageSize={}, active={}", userId, page, pageSize, active);
            }
        });
    }

    /**
     * Get cached user challenges (SYNC - needs result)
     */
    public <T> Optional<T> getCachedUserChallenges(Long userId, int page, int pageSize, Boolean active, Class<T> type) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = USER_CHALLENGES_PREFIX + userId + ":page:" + page + ":size:" + pageSize
                + ":active:" + (active != null ? active : "all");
        return getOptional(key, type);
    }

    /**
     * Invalidate all cached user challenges for a user (ASYNC - non-blocking)
     * Call this when a user creates, deletes, or updates a challenge
     */
    public void invalidateUserChallenges(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            deleteByPattern(USER_CHALLENGES_PREFIX + userId + ":*");
            logger.debug("Invalidated user challenges cache: userId={}", userId);
        });
    }

    /**
     * Prepend a newly created challenge to any cached first page of a user's challenge list.
     * Later pages are cleared because prepending shifts pagination boundaries.
     */
    public void prependCreatedChallengeToUserChallenges(Long userId, int defaultPageSize, Challenge challenge) {
        if (userId == null || challenge == null || challenge.getId() == null) {
            return;
        }
        executeAsync(() -> {
            Set<String> userChallengeKeys = scanKeys(USER_CHALLENGES_PREFIX + userId + ":page:*");
            if (userChallengeKeys != null && !userChallengeKeys.isEmpty()) {
                List<String> laterPageKeys = userChallengeKeys.stream()
                        .filter(key -> !key.contains(":page:1:size:"))
                        .toList();
                if (!laterPageKeys.isEmpty()) {
                    redisTemplate.delete(laterPageKeys);
                }
            }

            String pattern = USER_CHALLENGES_PREFIX + userId + ":page:1:size:*:active:*";
            Set<String> keys = scanKeys(pattern);
            if (keys == null || keys.isEmpty()) {
                logger.debug("No cached first-page user challenges to patch: userId={}", userId);
                return;
            }

            for (String key : keys) {
                UserChallengesResponse cached = get(key, UserChallengesResponse.class);
                if (cached == null || cached.getChallenges() == null) {
                    continue;
                }
                String activeToken = key.substring(key.lastIndexOf(":active:") + ":active:".length());
                if ("true".equals(activeToken) && !Boolean.TRUE.equals(challenge.getActive())) {
                    continue;
                }
                if ("false".equals(activeToken) && Boolean.TRUE.equals(challenge.getActive())) {
                    continue;
                }

                int pageSize = cached.getPageSize() != null ? cached.getPageSize() : defaultPageSize;
                List<Challenge> updatedChallenges = new ArrayList<>();
                updatedChallenges.add(challenge);
                cached.getChallenges().stream()
                        .filter(existing -> existing != null && !challenge.getId().equals(existing.getId()))
                        .limit(Math.max(pageSize - 1, 0))
                        .forEach(updatedChallenges::add);

                int totalCount = cached.getTotalCount() != null ? cached.getTotalCount() + 1 : updatedChallenges.size();
                UserChallengesResponse updated = UserChallengesResponse.create(
                        cached.getUserId(),
                        updatedChallenges,
                        cached.getCurrentPage() != null ? cached.getCurrentPage() : 1,
                        pageSize,
                        totalCount
                );
                save(key, updated, USER_CHALLENGES_TTL);
            }
            logger.debug("Prepended created challenge {} to cached user challenges for userId={}",
                    challenge.getId(), userId);
        });
    }

    // ==================== USE CASE 12: CHALLENGE PARTICIPANTS CACHING ====================

    /**
     * Cache challenge participants response (ASYNC - non-blocking)
     */
    public <T> void cacheChallengeParticipants(Long challengeId, T response) {
        if (challengeId == null || response == null) {
            return;
        }
        executeAsync(() -> {
            String key = CHALLENGE_PARTICIPANTS_PREFIX + challengeId;
            if (save(key, response, CHALLENGE_PARTICIPANTS_TTL)) {
                logger.debug("Cached challenge participants: challengeId={}", challengeId);
            }
        });
    }

    /**
     * Get cached challenge participants (SYNC - needs result)
     */
    public <T> Optional<T> getCachedChallengeParticipants(Long challengeId, Class<T> type) {
        if (challengeId == null) {
            return Optional.empty();
        }
        String key = CHALLENGE_PARTICIPANTS_PREFIX + challengeId;
        return getOptional(key, type);
    }

    /**
     * Invalidate challenge participants cache (ASYNC - non-blocking)
     * Call when users join/leave a challenge
     */
    public void invalidateChallengeParticipants(Long challengeId) {
        if (challengeId == null) {
            return;
        }
        executeAsync(() -> {
            String key = CHALLENGE_PARTICIPANTS_PREFIX + challengeId;
            delete(key);
            logger.debug("Invalidated challenge participants cache: challengeId={}", challengeId);
        });
    }

    // ==================== USE CASE 13: HASHTAG CHALLENGES CACHING ====================

    /**
     * Cache challenges by hashtag response (ASYNC - non-blocking)
     */
    public <T> void cacheHashtagChallenges(List<String> hashtags, int page, int pageSize, T response) {
        if (hashtags == null || hashtags.isEmpty() || response == null) {
            return;
        }
        executeAsync(() -> {
            String hashtagKey = String.join(",", hashtags.stream().sorted().toList());
            String key = HASHTAG_CHALLENGES_PREFIX + hashtagKey + ":page:" + page + ":size:" + pageSize;
            if (save(key, response, HASHTAG_CHALLENGES_TTL)) {
                logger.debug("Cached hashtag challenge results for page={}", page);
            }
        });
    }

    /**
     * Get cached hashtag challenges (SYNC - needs result)
     */
    public <T> Optional<T> getCachedHashtagChallenges(List<String> hashtags, int page, int pageSize, Class<T> type) {
        if (hashtags == null || hashtags.isEmpty()) {
            return Optional.empty();
        }
        String hashtagKey = String.join(",", hashtags.stream().sorted().toList());
        String key = HASHTAG_CHALLENGES_PREFIX + hashtagKey + ":page:" + page + ":size:" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate all hashtag challenge caches (ASYNC - non-blocking)
     */
    public void invalidateHashtagChallenges() {
        executeAsync(() -> {
            deleteByPattern(HASHTAG_CHALLENGES_PREFIX + "*");
            logger.debug("Invalidated all hashtag challenges cache");
        });
    }

    // ==================== USE CASE 14: COMMENTS CACHING ====================

    /**
     * Cache comments for a submission page (ASYNC - non-blocking)
     */
    public <T> void cacheCommentsForSubmission(Long submissionId, int page, int size, T comments) {
        if (submissionId == null || comments == null) {
            return;
        }
        executeAsync(() -> {
            String key = COMMENTS_PREFIX + submissionId + ":page:" + page + ":size:" + size;
            if (save(key, comments, COMMENTS_TTL)) {
                logger.debug("Cached comments for submission: submissionId={}, page={}", submissionId, page);
            }
        });
    }

    /**
     * Get cached comments for a submission page (SYNC - needs result)
     */
    public <T> Optional<T> getCachedCommentsForSubmission(Long submissionId, int page, int size, Class<T> type) {
        if (submissionId == null) {
            return Optional.empty();
        }
        String key = COMMENTS_PREFIX + submissionId + ":page:" + page + ":size:" + size;
        return getOptional(key, type);
    }

    /**
     * Invalidate comments cache for a submission (ASYNC - non-blocking)
     */
    public void invalidateCommentsForSubmission(Long submissionId) {
        if (submissionId == null) {
            return;
        }
        executeAsync(() -> {
            deleteByPattern(COMMENTS_PREFIX + submissionId + ":*");
            logger.debug("Invalidated comments cache for submission: submissionId={}", submissionId);
        });
    }


    // ==================== USE CASE 15: GLORY CACHING ====================

    /**
     * Cache glory count for a submission (ASYNC - non-blocking)
     */
    public void cacheGloryCount(Long submissionId, Long count) {
        if (submissionId == null || count == null) {
            return;
        }
        executeAsync(() -> {
            String key = GLORY_COUNT_PREFIX + submissionId;
            if (save(key, count, GLORY_TTL)) {
                logger.debug("Cached glory count for submission: submissionId={}, count={}", submissionId, count);
            }
        });
    }

    /**
     * Get cached glory count for a submission (SYNC - needs result)
     */
    public Optional<Long> getCachedGloryCount(Long submissionId) {
        if (submissionId == null) {
            return Optional.empty();
        }
        String key = GLORY_COUNT_PREFIX + submissionId;
        return getOptional(key, Long.class);
    }

    /**
     * Cache whether a user has given glory to a submission (ASYNC - non-blocking)
     */
    public void cacheGloryCheck(Long submissionId, Long userId, boolean hasGlory) {
        if (submissionId == null || userId == null) {
            return;
        }
        executeAsync(() -> {
            String key = GLORY_CHECK_PREFIX + submissionId + ":user:" + userId;
            if (save(key, hasGlory, GLORY_TTL)) {
                logger.debug("Cached glory check: submissionId={}, userId={}, hasGlory={}", submissionId, userId, hasGlory);
            }
        });
    }

    /**
     * Get cached glory check for a user on a submission (SYNC - needs result)
     */
    public Optional<Boolean> getCachedGloryCheck(Long submissionId, Long userId) {
        if (submissionId == null || userId == null) {
            return Optional.empty();
        }
        String key = GLORY_CHECK_PREFIX + submissionId + ":user:" + userId;
        return getOptional(key, Boolean.class);
    }

    /**
     * Invalidate all glory caches for a submission (ASYNC - non-blocking)
     */
    public void invalidateGloryCaches(Long submissionId) {
        if (submissionId == null) {
            return;
        }
        executeAsync(() -> {
            delete(GLORY_COUNT_PREFIX + submissionId);
            deleteByPattern(GLORY_CHECK_PREFIX + submissionId + ":*");
            logger.debug("Invalidated glory caches for submission: submissionId={}", submissionId);
        });
    }

    // ==================== USE CASE 16: FEED CACHING ====================

    /**
     * Cache a feed response (ASYNC - non-blocking)
     */
    public <T> void cacheFeed(String feedType, Long userId, int page, int pageSize, T response) {
        if (feedType == null || response == null) {
            return;
        }
        executeAsync(() -> {
            String key = FEED_PREFIX + feedType + ":user:" + (userId != null ? userId : "anon")
                    + ":page:" + page + ":size:" + pageSize;
            if (save(key, response, FEED_TTL)) {
                logger.debug("Cached feed: type={}, userId={}, page={}", feedType, userId, page);
            }
        });
    }

    /**
     * Get cached feed response (SYNC - needs result)
     */
    public <T> Optional<T> getCachedFeed(String feedType, Long userId, int page, int pageSize, Class<T> type) {
        if (feedType == null) {
            return Optional.empty();
        }
        String key = FEED_PREFIX + feedType + ":user:" + (userId != null ? userId : "anon")
                + ":page:" + page + ":size:" + pageSize;
        return getOptional(key, type);
    }

    /**
     * Invalidate feed caches for a specific feed type (ASYNC - non-blocking)
     */
    public void invalidateFeed(String feedType) {
        if (feedType == null) {
            return;
        }
        executeAsync(() -> {
            deleteByPattern(FEED_PREFIX + feedType + ":*");
            logger.debug("Invalidated feed cache: type={}", feedType);
        });
    }

    /**
     * Invalidate all feed caches (ASYNC - non-blocking)
     * Use when new submissions or interactions occur
     */
    public void invalidateAllFeeds() {
        executeAsync(() -> {
            deleteByPattern(FEED_PREFIX + "*");
            logger.debug("Invalidated all feed caches");
        });
    }

    /**
     * Invalidate feed caches for a specific user (ASYNC - non-blocking)
     */
    public void invalidateUserFeeds(Long userId) {
        if (userId == null) {
            return;
        }
        executeAsync(() -> {
            deleteByPattern(FEED_PREFIX + "*:user:" + userId + ":*");
            logger.debug("Invalidated feed caches for userId={}", userId);
        });
    }

    // ==================== FOLLOWING FEED TIMELINE (sorted set per user) ====================
    //
    // Instead of caching entire FeedResponse JSON blobs, the FOLLOWING feed uses
    // a Redis Sorted Set per user:  feed:timeline:{userId}
    //   score  = submission createdAt millis  (natural chronological order)
    //   member = submissionId
    //
    // On new submission → fan-out ZADD to each follower's set (if it exists)
    // On read           → ZREVRANGE with offset+limit, then hydrate from DB
    // On cache miss     → rebuild from DB, populate sorted set, set TTL

    /**
     * Fan out a new submission to all given followers' timelines (ASYNC).
     * Only pushes into timelines that already exist (warm caches).
     * Cold caches will pick up the submission on their next DB rebuild.
     */
    public void fanOutToFollowerTimelines(Set<Long> followerIds, Long submissionId, double score) {
        if (followerIds == null || followerIds.isEmpty() || submissionId == null) return;
        executeAsync(() -> {
            int pushed = 0;
            for (Long followerId : followerIds) {
                try {
                    String key = FEED_TIMELINE_PREFIX + followerId;
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                        redisTemplate.opsForZSet().add(key, submissionId.toString(), score);
                        // Trim oldest entries to cap memory
                        Long size = redisTemplate.opsForZSet().zCard(key);
                        if (size != null && size > FEED_TIMELINE_MAX_SIZE) {
                            redisTemplate.opsForZSet().removeRange(key, 0, size - FEED_TIMELINE_MAX_SIZE - 1);
                        }
                        pushed++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to push submission {} to timeline of user {}: {}",
                            submissionId, followerId, e.getClass().getSimpleName());
                }
            }
            logger.debug("Fan-out: pushed submission {} to {}/{} follower timelines",
                    submissionId, pushed, followerIds.size());
        });
    }

    /**
     * Check if a user's timeline sorted set exists in cache.
     */
    public boolean timelineExists(Long userId) {
        if (userId == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(FEED_TIMELINE_PREFIX + userId));
        } catch (Exception e) {
            logger.warn("Error checking timeline existence for userId={}: {}", userId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Get submission IDs from a user's timeline (paginated, reverse chronological).
     */
    public List<Long> getTimelineIds(Long userId, int offset, int count) {
        if (userId == null || count <= 0) return List.of();
        try {
            String key = FEED_TIMELINE_PREFIX + userId;
            Set<Object> results = redisTemplate.opsForZSet().reverseRange(key, offset, (long) offset + count - 1);
            if (results == null || results.isEmpty()) return List.of();
            return results.stream()
                    .map(o -> Long.parseLong(o.toString()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error reading timeline for userId={}: {}", userId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Get total number of entries in a user's timeline.
     */
    public long getTimelineSize(Long userId) {
        if (userId == null) return 0;
        try {
            Long size = redisTemplate.opsForZSet().zCard(FEED_TIMELINE_PREFIX + userId);
            return size != null ? size : 0;
        } catch (Exception e) {
            logger.warn("Error getting timeline size for userId={}: {}", userId, e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Rebuild a user's timeline sorted set from DB data.
     * Typically called on cache miss (first feed request after TTL expiry).
     */
    public void rebuildTimeline(Long userId, List<Long> submissionIds, List<Double> scores) {
        if (userId == null || submissionIds == null || scores == null
                || submissionIds.isEmpty() || submissionIds.size() != scores.size()) return;
        try {
            String key = FEED_TIMELINE_PREFIX + userId;
            Set<ZSetOperations.TypedTuple<Object>> tuples = new java.util.LinkedHashSet<>();
            for (int i = 0; i < submissionIds.size(); i++) {
                tuples.add(ZSetOperations.TypedTuple.of(submissionIds.get(i).toString(), scores.get(i)));
            }
            redisTemplate.opsForZSet().add(key, tuples);
            redisTemplate.expire(key, FEED_TIMELINE_TTL);
            logger.debug("Rebuilt timeline for userId={} with {} entries", userId, submissionIds.size());
        } catch (Exception e) {
            logger.error("Error rebuilding timeline for userId={}: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Invalidate a user's timeline sorted set synchronously.
     * Access-revocation paths call this before returning; live feed hydration
     * still revalidates visibility if Redis is unavailable.
     */
    public void invalidateTimeline(Long userId) {
        if (userId == null) return;
        delete(FEED_TIMELINE_PREFIX + userId);
        logger.debug("Invalidated timeline for userId={}", userId);
    }

    /**
     * Get all follower IDs from the cached followers sorted set.
     * Returns empty set if the sorted set doesn't exist.
     */
    public Set<Long> getCachedFollowerIds(Long userId) {
        if (userId == null) return Set.of();
        try {
            Set<Object> members = redisTemplate.opsForZSet().range(FOLLOW_FOLLOWERS_PREFIX + userId, 0, -1);
            if (members == null || members.isEmpty()) return Set.of();
            return members.stream()
                    .map(o -> Long.parseLong(o.toString()))
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.warn("Error getting cached follower IDs for userId={}: {}", userId, e.getClass().getSimpleName());
            return Set.of();
        }
    }

    // ==================== DIRTY LOGIN SET (for hourly DB flush) ====================

    /**
     * Mark a userId as needing a last_login_at DB update.
     * Uses a Redis Set so duplicates are naturally de-duped — O(1).
     * Called from stampLastLogin() on every getUser() hit.
     */
    public void markLoginDirty(Long userId) {
        if (userId == null) return;
        executeAsync(() -> {
            try {
                redisTemplate.opsForSet().add(DIRTY_LOGIN_SET_KEY, userId.toString());
                logger.debug("Marked userId={} as dirty-login", userId);
            } catch (Exception e) {
                logger.warn("Failed to mark dirty login for userId={}: {}", userId, e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Atomically drain all dirty user IDs from the Redis set.
     * Uses SPOP in a loop so no ID is lost even if the scheduler crashes mid-way.
     *
     * @return list of user IDs that need a DB flush, or empty list on error
     */
    public List<Long> drainDirtyLogins() {
        List<Long> ids = new ArrayList<>();
        try {
            // SPOP removes and returns a member — repeat until empty
            Object member;
            while ((member = redisTemplate.opsForSet().pop(DIRTY_LOGIN_SET_KEY)) != null) {
                ids.add(Long.parseLong(member.toString()));
            }
            logger.debug("Drained {} dirty-login user IDs from Redis", ids.size());
        } catch (Exception e) {
            logger.warn("Failed to drain dirty logins: {}", e.getClass().getSimpleName());
        }
        return ids;
    }
}
