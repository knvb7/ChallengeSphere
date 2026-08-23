package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.EphemeralTableGateway;
import com.ironside.challengesphere.repository.LeaderboardEntryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Compatibility facade retained under its historical type name while the
 * backend moves off Redis. Short-lived values are stored in the DynamoDB TTL
 * table; durable feeds, follows, and leaderboards are read from their main-table
 * projections. Pattern invalidation deliberately never scans a table.
 */
@Service
public class RedisCacheServiceImpl {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final String STATE = "STATE";

    private final EphemeralTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObjectProvider<LeaderboardEntryRepository> leaderboardRepository;

    public RedisCacheServiceImpl(
            EphemeralTableGateway gateway,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<LeaderboardEntryRepository> leaderboardRepository
    ) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.leaderboardRepository = leaderboardRepository;
    }

    public boolean isHealthy() {
        return true;
    }

    public <T> boolean save(String key, T value, Duration ttl) {
        if (!hasText(key) || value == null || ttl == null || ttl.isNegative() || ttl.isZero()) return false;
        Instant now = clock.instant();
        DynamoDbItem item = DynamoDbItem.create(cacheKey(key), "CACHE", encode(value), now);
        item.setExpiresAt(now.plus(ttl).getEpochSecond());
        gateway.put(item);
        return true;
    }

    public <T> void save(String key, T value, long timeout, TimeUnit unit) {
        if (unit != null) save(key, value, Duration.ofMillis(unit.toMillis(timeout)));
    }

    public <T> T get(String key, Class<T> type) {
        return getOptional(key, type).orElse(null);
    }

    public <T> Optional<T> getOptional(String key, Class<T> type) {
        if (!hasText(key) || type == null) return Optional.empty();
        Optional<DynamoDbItem> stored = gateway.get(cacheKey(key), true);
        if (stored.isEmpty()) return Optional.empty();
        DynamoDbItem item = stored.get();
        if (item.getExpiresAt() == null || item.getExpiresAt() <= clock.instant().getEpochSecond()) {
            gateway.delete(cacheKey(key));
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(item.getPayloadJson(), type));
        } catch (JsonProcessingException invalidPayload) {
            gateway.delete(cacheKey(key));
            return Optional.empty();
        }
    }

    public boolean delete(String key) {
        if (!hasText(key)) return false;
        boolean existed = gateway.get(cacheKey(key), true).isPresent();
        gateway.delete(cacheKey(key));
        return existed;
    }

    public void deleteAll(Collection<String> keys) {
        if (keys != null) keys.forEach(this::delete);
    }

    /** Wildcard deletion is unsupported because the runtime IAM policy forbids Scan. */
    public void deleteByPattern(String pattern) {
        // Namespace-versioned materializations expire naturally; specific keys use delete().
    }

    public boolean exists(String key) { return getOptional(key, Object.class).isPresent(); }

    public boolean expire(String key, Duration ttl) {
        Optional<Object> value = getOptional(key, Object.class);
        return value.isPresent() && save(key, value.get(), ttl);
    }

    public long getTtl(String key) {
        return gateway.get(cacheKey(key), true)
                .map(DynamoDbItem::getExpiresAt)
                .map(expiry -> Math.max(-1L, expiry - clock.instant().getEpochSecond()))
                .orElse(-2L);
    }

    public <T> void cacheChallenge(String challengeId, T challenge) {
        if (hasText(challengeId)) save("challenge:" + challengeId, challenge, DEFAULT_TTL);
    }

    public <T> Optional<T> getCachedChallenge(String challengeId, Class<T> type) {
        return getOptional("challenge:" + challengeId, type);
    }

    public void invalidateChallenge(String challengeId) { delete("challenge:" + challengeId); }
    public void clearChallengeCache(String challengeId) { invalidateChallenge(challengeId); }

    public record LeaderboardEntry(String userId, double score, long rank) {}

    public void addToLeaderboard(String challengeId, String userId, double score) {
        // Main-table leaderboard projections are written by LeaderboardEntryRepository.
    }

    public void removeFromLeaderboard(String challengeId, String userId) {
        // Main-table leaderboard projections are authoritative.
    }

    public List<LeaderboardEntry> getTopScoresWithRank(String challengeId, int count) {
        LeaderboardEntryRepository repository = leaderboardRepository.getIfAvailable();
        if (repository == null || !hasText(challengeId) || count <= 0) return List.of();
        List<com.ironside.challengesphere.model.LeaderboardEntry> entries = repository.findActiveByChallengeId(challengeId);
        return java.util.stream.IntStream.range(0, Math.min(count, entries.size()))
                .mapToObj(index -> {
                    com.ironside.challengesphere.model.LeaderboardEntry entry = entries.get(index);
                    return new LeaderboardEntry(entry.getUser().getId(), entry.getScore(), index + 1L);
                }).toList();
    }

    public <T> void cacheUserProfile(T profile) {
        if (profile instanceof User user && user.getId() != null) {
            save("user:profile:" + user.getId(), profile, DEFAULT_TTL);
        }
    }

    public <T> Optional<T> getCachedUserProfile(
            String userId, String email, String username, Class<T> type
    ) {
        return hasText(userId) ? getOptional("user:profile:" + userId, type) : Optional.empty();
    }

    public void invalidateUserProfile(String userId) { delete("user:profile:" + userId); }
    public void clearUserCache(String userId) { invalidateUserProfile(userId); }
    public void invalidateAllUserCaches(String userId) { invalidateUserProfile(userId); }

    public void addFollow(String followerId, String followingId, long timestampMillis) {
        // FollowRepository writes both directional projections transactionally.
    }

    public void removeFollow(String followerId, String followingId) {
        // FollowRepository removes both directional projections transactionally.
    }

    public String getFollowingKey(String userId) { return "follows:following:" + userId; }
    public String getFollowersKey(String userId) { return "follows:followers:" + userId; }
    public boolean followSetExists(String key) { return false; }
    public List<String> getFollowPage(String key, long offset, long count) { return List.of(); }
    public long getFollowCount(String key) { return 0L; }
    public void backfillFollowSet(String key, List<String> userIds, List<Long> timestamps) {
        // Durable follow projections already contain this ordering.
    }

    /** Durable following timelines live in the main table; this hook is obsolete. */
    public void invalidateTimeline(String userId) {
        // Compatibility no-op for callers that still invalidate historical cache names.
    }

    public void invalidateSubmission(String submissionId) { delete("submission:" + submissionId); }
    public void invalidateUserSubmissions(String userId, String challengeId) {}
    public void invalidateRecentChallenges() {}
    public void invalidateRecentAndTrendingChallenges() {}
    public void invalidateHashtagChallenges() {}
    public void invalidateChallengeParticipants(String challengeId) {}
    public void invalidateUserChallenges(String userId) {}
    public void invalidateCommentsForSubmission(String submissionId) {}
    public void invalidateGloryCaches(String submissionId) {}
    public void invalidateAllFeeds() {}
    public void invalidateFeed(String feedType) {}
    public void invalidateUserFeeds(String userId) {}

    public void clearAllCaches() {
        // Deliberately no table-wide operation. TTL and namespace rotation retire entries.
    }

    public <T> T getOrCompute(String key, Class<T> type, Duration ttl, Supplier<T> supplier) {
        return getOptional(key, type).orElseGet(() -> {
            T value = supplier.get();
            if (value != null) save(key, value, ttl);
            return value;
        });
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize cache value", exception);
        }
    }

    private static DynamoDbKey cacheKey(String logicalKey) {
        return DynamoDbKeys.key("CACHE#" + sha256(logicalKey), STATE);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

}
