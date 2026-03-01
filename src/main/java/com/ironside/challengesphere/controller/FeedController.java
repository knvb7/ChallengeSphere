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
import com.ironside.challengesphere.service.FeedService;

import jakarta.validation.Valid;

/**
 * REST Controller for Feed operations
 * Provides endpoints for various feed types
 */
@RestController
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
    public ResponseEntity<FeedResponse> getFeed(@Valid @RequestBody GetFeedRequest request) 
            throws ChallengeSphereException {
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
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) throws ChallengeSphereException {
        logger.info("Following feed request: userId={}, page={}, pageSize={}", userId, page, pageSize);
        FeedResponse response = feedService.getFollowingFeed(userId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Get trending feed - popular submissions based on engagement
     * GET /api/v1/feed/trending
     */
    @GetMapping("/trending")
    public ResponseEntity<FeedResponse> getTrendingFeed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        logger.info("Trending feed request: page={}, pageSize={}, userId={}", page, pageSize, userId);
        FeedResponse response = feedService.getTrendingFeed(page, pageSize, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get explore feed - all public submissions sorted by recency
     * GET /api/v1/feed/explore
     */
    @GetMapping("/explore")
    public ResponseEntity<FeedResponse> getExploreFeed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        logger.info("Explore feed request: page={}, pageSize={}, userId={}", page, pageSize, userId);
        FeedResponse response = feedService.getExploreFeed(page, pageSize, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get feed for a specific challenge
     * GET /api/v1/feed/challenge/{challengeId}
     */
    @GetMapping("/challenge/{challengeId}")
    public ResponseEntity<FeedResponse> getChallengeFeed(
            @PathVariable Long challengeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        logger.info("Challenge feed request: challengeId={}, page={}, pageSize={}", challengeId, page, pageSize);
        FeedResponse response = feedService.getChallengeFeed(challengeId, page, pageSize, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get feed for a specific user's submissions
     * GET /api/v1/feed/user/{targetUserId}
     */
    @GetMapping("/user/{targetUserId}")
    public ResponseEntity<FeedResponse> getUserFeed(
            @PathVariable Long targetUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long currentUserId) throws ChallengeSphereException {
        logger.info("User feed request: targetUserId={}, page={}, pageSize={}", targetUserId, page, pageSize);
        FeedResponse response = feedService. getUserFeed(targetUserId, page, pageSize, currentUserId);
        return ResponseEntity.ok(response);
    }
}

