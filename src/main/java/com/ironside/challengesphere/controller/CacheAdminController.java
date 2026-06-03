package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/admin/cache")
public class CacheAdminController {

    private static final Logger logger = LogManager.getLogger(CacheAdminController.class);

    private static final List<String> SAFE_PATTERN_PREFIXES = List.of(
            "challenge:",
            "user:",
            "submission:",
            "leaderboard:",
            "recent:",
            "trending:",
            "verification:",
            "user:challenges:",
            "challenge:participants:",
            "challenge:hashtag:",
            "comments:submission:",
            "glory:count:",
            "glory:check:",
            "feed:",
            "follows:"
    );

    private static final List<String> PROTECTED_PREFIXES = List.of(
            "auth:",
            "session:",
            "rate_limit:",
            "dirty:last_login"
    );

    private final RedisCacheServiceImpl cacheService;

    public CacheAdminController(RedisCacheServiceImpl cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("redisHealthy", cacheService.isHealthy()));
    }

    @PostMapping("/challenges/{challengeId}/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateChallenge(@PathVariable Long challengeId) {
        cacheService.clearChallengeCache(challengeId);
        return accepted("challenge", challengeId);
    }

    @PostMapping("/users/{userId}/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateUser(@PathVariable Long userId) {
        cacheService.clearUserCache(userId);
        cacheService.invalidateAllUserCaches(userId);
        cacheService.invalidateUserChallenges(userId);
        cacheService.invalidateUserFeeds(userId);
        cacheService.invalidateTimeline(userId);
        return accepted("user", userId);
    }

    @PostMapping("/submissions/{submissionId}/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateSubmission(@PathVariable Long submissionId) {
        cacheService.invalidateSubmission(submissionId);
        cacheService.invalidateCommentsForSubmission(submissionId);
        cacheService.invalidateGloryCaches(submissionId);
        cacheService.invalidateAllFeeds();
        return accepted("submission", submissionId);
    }

    @PostMapping("/users/{userId}/challenges/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateUserChallenges(@PathVariable Long userId) {
        cacheService.invalidateUserChallenges(userId);
        return accepted("user-challenges", userId);
    }

    @PostMapping("/challenges/{challengeId}/participants/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateChallengeParticipants(@PathVariable Long challengeId) {
        cacheService.invalidateChallengeParticipants(challengeId);
        return accepted("challenge-participants", challengeId);
    }

    @PostMapping("/users/{userId}/submissions/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateUserSubmissions(
            @PathVariable Long userId,
            @RequestParam Long challengeId) {
        cacheService.invalidateUserSubmissions(userId, challengeId);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "cache", "user-submissions",
                "userId", userId,
                "challengeId", challengeId
        ));
    }

    @PostMapping("/feeds/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateAllFeeds() {
        cacheService.invalidateAllFeeds();
        return accepted("feeds", "all");
    }

    @PostMapping("/feeds/{feedType}/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateFeed(@PathVariable String feedType) {
        cacheService.invalidateFeed(feedType);
        return accepted("feed", feedType);
    }

    @PostMapping("/users/{userId}/feeds/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateUserFeeds(@PathVariable Long userId) {
        cacheService.invalidateUserFeeds(userId);
        return accepted("user-feeds", userId);
    }

    @PostMapping("/users/{userId}/timeline/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateTimeline(@PathVariable Long userId) {
        cacheService.invalidateTimeline(userId);
        return accepted("timeline", userId);
    }

    @PostMapping("/submissions/{submissionId}/comments/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateComments(@PathVariable Long submissionId) {
        cacheService.invalidateCommentsForSubmission(submissionId);
        return accepted("comments", submissionId);
    }

    @PostMapping("/submissions/{submissionId}/glories/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateGlories(@PathVariable Long submissionId) {
        cacheService.invalidateGloryCaches(submissionId);
        return accepted("glories", submissionId);
    }

    @PostMapping("/hashtags/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateHashtags() {
        cacheService.invalidateHashtagChallenges();
        return accepted("hashtag-challenges", "all");
    }

    @PostMapping("/challenges/recent/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateRecentChallenges() {
        cacheService.invalidateRecentChallenges();
        return accepted("recent-challenges", "all");
    }

    @PostMapping("/challenges/recent-trending/invalidate")
    public ResponseEntity<Map<String, Object>> invalidateRecentAndTrendingChallenges() {
        cacheService.invalidateRecentAndTrendingChallenges();
        return accepted("recent-and-trending-challenges", "all");
    }

    @DeleteMapping("/keys")
    public ResponseEntity<Map<String, Object>> deleteKey(@Valid @RequestBody CacheKeyRequest request)
            throws ChallengeSphereException {
        validateDirectKey(request.key());
        boolean deleted = cacheService.delete(request.key());
        logger.warn("Manual cache key deletion requested: key={}, deleted={}", request.key(), deleted);
        return ResponseEntity.ok(Map.of(
                "status", deleted ? "deleted" : "not_found",
                "key", request.key()
        ));
    }

    @DeleteMapping("/patterns")
    public ResponseEntity<Map<String, Object>> deletePattern(@Valid @RequestBody CachePatternRequest request)
            throws ChallengeSphereException {
        validatePattern(request.pattern());
        cacheService.deleteByPattern(request.pattern());
        logger.warn("Manual cache pattern deletion requested: pattern={}", request.pattern());
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "pattern", request.pattern()
        ));
    }

    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> clearAll(@RequestParam(defaultValue = "false") boolean confirm)
            throws ChallengeSphereException {
        if (!confirm) {
            throw new ChallengeSphereException("confirm=true is required to clear all caches",
                    HttpStatus.BAD_REQUEST, logger);
        }
        cacheService.clearAllCaches();
        logger.warn("Manual all-cache clear requested");
        return accepted("all", "all");
    }

    private ResponseEntity<Map<String, Object>> accepted(String cache, Object target) {
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "cache", cache,
                "target", target
        ));
    }

    private void validateDirectKey(String key) throws ChallengeSphereException {
        if (!StringUtils.hasText(key) || key.contains("*")) {
            throw new ChallengeSphereException("A concrete cache key without wildcards is required",
                    HttpStatus.BAD_REQUEST, logger);
        }
        if (hasProtectedPrefix(key)) {
            throw new ChallengeSphereException("Protected auth/session/rate-limit keys cannot be manually deleted here",
                    HttpStatus.BAD_REQUEST, logger);
        }
    }

    private void validatePattern(String pattern) throws ChallengeSphereException {
        if (!StringUtils.hasText(pattern) || !pattern.contains("*")) {
            throw new ChallengeSphereException("A cache pattern containing * is required",
                    HttpStatus.BAD_REQUEST, logger);
        }
        if ("*".equals(pattern.trim()) || hasProtectedPrefix(pattern)) {
            throw new ChallengeSphereException("Protected or global patterns are not allowed",
                    HttpStatus.BAD_REQUEST, logger);
        }
        boolean allowed = SAFE_PATTERN_PREFIXES.stream().anyMatch(pattern::startsWith);
        if (!allowed) {
            throw new ChallengeSphereException("Pattern must start with a known cache prefix",
                    HttpStatus.BAD_REQUEST, logger);
        }
    }

    private boolean hasProtectedPrefix(String keyOrPattern) {
        return PROTECTED_PREFIXES.stream().anyMatch(keyOrPattern::startsWith);
    }

    public record CacheKeyRequest(@NotBlank String key) {
    }

    public record CachePatternRequest(@NotBlank String pattern) {
    }
}
