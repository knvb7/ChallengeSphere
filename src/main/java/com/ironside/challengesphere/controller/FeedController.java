package com.ironside.challengesphere.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironside.challengesphere.api.request.GetFeedRequest;
import com.ironside.challengesphere.api.response.FeedResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.FeedService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * REST Controller for Feed operations
 * Provides endpoints for various feed types
 */
@RestController
@Validated
@RequestMapping("/api/v1/feed")
public class FeedController {

    private static final Logger logger = LogManager.getLogger(FeedController.class);

    @Autowired
    private FeedService feedService;

    /**
     * Get personalized feed based on request parameters
     * POST /api/v1/feed
     */
    @PostMapping
    public ResponseEntity<FeedResponse> getFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GetFeedRequest request) 
            throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal));
        logger.info("Feed request received: userId={}, type={}, page={}", 
                request.getUserId(), request.getFeedType(), request.getPage());
        FeedResponse response = feedService.getFeed(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get following feed - submissions from users the current user follows
     * GET /api/v1/feed/following/{userId}
     */
    @GetMapping("/following/{userId}")
    public ResponseEntity<FeedResponse> getFollowingFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Following feed request: userId={}, page={}, pageSize={}", actorUserId, page, pageSize);
        FeedResponse response = feedService.getFollowingFeed(actorUserId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Get connections feed - activity from followers and people the current user follows.
     * GET /api/v1/feed/connections/{userId}
     */
    @GetMapping("/connections/{userId}")
    public ResponseEntity<FeedResponse> getConnectionsFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Connections feed request: userId={}, page={}, pageSize={}", actorUserId, page, pageSize);
        FeedResponse response = feedService.getConnectionsFeed(actorUserId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Get trending feed - popular submissions based on engagement
     * GET /api/v1/feed/trending
     */
    @GetMapping("/trending")
    public ResponseEntity<FeedResponse> getTrendingFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Trending feed request: page={}, pageSize={}, userId={}", page, pageSize, actorUserId);
        FeedResponse response = feedService.getTrendingFeed(page, pageSize, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get explore feed - all public submissions sorted by recency
     * GET /api/v1/feed/explore
     */
    @GetMapping("/explore")
    public ResponseEntity<FeedResponse> getExploreFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Explore feed request: page={}, pageSize={}, userId={}", page, pageSize, actorUserId);
        FeedResponse response = feedService.getExploreFeed(page, pageSize, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get feed for a specific challenge
     * GET /api/v1/feed/challenge/{challengeId}
     */
    @GetMapping("/challenge/{challengeId}")
    public ResponseEntity<FeedResponse> getChallengeFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long challengeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Challenge feed request: challengeId={}, page={}, pageSize={}", challengeId, page, pageSize);
        FeedResponse response = feedService.getChallengeFeed(challengeId, page, pageSize, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get feed for a specific user's submissions
     * GET /api/v1/feed/user/{targetUserId}
     */
    @GetMapping("/user/{targetUserId}")
    public ResponseEntity<FeedResponse> getUserFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long targetUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long currentUserId) throws ChallengeSphereException {
        logger.info("User feed request: targetUserId={}, page={}, pageSize={}", targetUserId, page, pageSize);
        FeedResponse response = feedService. getUserFeed(targetUserId, page, pageSize, SecurityAccess.requireUserId(principal));
        return ResponseEntity.ok(response);
    }
}
