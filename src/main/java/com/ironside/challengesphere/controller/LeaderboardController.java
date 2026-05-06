package com.ironside.challengesphere.controller;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironside.challengesphere.api.request.GetLeaderboardRequest;
import com.ironside.challengesphere.api.request.UpdateLeaderboardScoreRequest;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.LeaderboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST Controller for Leaderboard operations
 */
@RestController
@RequestMapping("/api/v1/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private static final Logger logger = LogManager.getLogger(LeaderboardController.class);

    private final LeaderboardService leaderboardService;

    /**
     * Get leaderboard for a challenge
     */
    @GetMapping("/challenge/{challengeId}")
    @Operation(summary = "Get leaderboard for a challenge", 
               description = "Returns the top participants ranked by their scores")
    public ResponseEntity<LeaderboardResponse> getLeaderboard(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Parameter(description = "Challenge ID") Long challengeId,
            @RequestParam(defaultValue = "10") @Parameter(description = "Number of entries to return (max 100)") Integer limit,
            @RequestParam(required = false) @Parameter(description = "Optional user ID to include their rank") Long userId,
            @RequestParam(defaultValue = "OVERALL") @Parameter(description = "Leaderboard type: SUBMISSIONS, GLORIES, STREAK, OVERALL") String type) throws ChallengeSphereException {
        
        logger.info("GET /api/v1/leaderboard/challenge/{} - limit: {}, userId: {}, type: {}", 
            challengeId, limit, userId, type);
        
        Long actorUserId = SecurityAccess.requireUserId(principal);
        GetLeaderboardRequest request = GetLeaderboardRequest.builder()
                .challengeId(challengeId)
                .limit(Math.min(limit, 100)) // Cap at 100
                .userId(userId == null ? actorUserId : userId)
                .type(GetLeaderboardRequest.LeaderboardType.valueOf(type.toUpperCase()))
                .build();
        
        LeaderboardResponse response = leaderboardService.getLeaderboard(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get user's rank in a challenge leaderboard
     */
    @GetMapping("/challenge/{challengeId}/user/{userId}/rank")
    @Operation(summary = "Get user's rank in a challenge leaderboard", 
               description = "Returns the user's current rank, score, and percentile")
    public ResponseEntity<LeaderboardResponse.UserRankDTO> getUserRank(
            @PathVariable @Parameter(description = "Challenge ID") Long challengeId,
            @PathVariable @Parameter(description = "User ID") Long userId) throws ChallengeSphereException {
        
        logger.info("GET /api/v1/leaderboard/challenge/{}/user/{}/rank", challengeId, userId);
        
        LeaderboardResponse.UserRankDTO rank = leaderboardService.getUserRank(challengeId, userId);
        return ResponseEntity.ok(rank);
    }

    /**
     * Update a user's leaderboard score (admin/internal use)
     */
    @PostMapping("/score")
    @Operation(summary = "Update user's leaderboard score", 
               description = "Manually update a user's score in the leaderboard (admin use)")
    public ResponseEntity<Map<String, String>> updateScore(
            @Valid @RequestBody UpdateLeaderboardScoreRequest request) throws ChallengeSphereException {
        
        logger.info("POST /api/v1/leaderboard/score - challengeId: {}, userId: {}, score: {}", 
            request.getChallengeId(), request.getUserId(), request.getScore());
        
        leaderboardService.updateScore(request);
        return ResponseEntity.ok(Map.of("message", "Score updated successfully"));
    }

    /**
     * Rebuild leaderboard from database (admin use)
     */
    @PostMapping("/challenge/{challengeId}/rebuild")
    @Operation(summary = "Rebuild leaderboard from database", 
               description = "Recalculates all scores from database (admin use)")
    public ResponseEntity<Map<String, String>> rebuildLeaderboard(
            @PathVariable @Parameter(description = "Challenge ID") Long challengeId) throws ChallengeSphereException {
        
        logger.info("POST /api/v1/leaderboard/challenge/{}/rebuild", challengeId);
        
        leaderboardService.rebuildLeaderboard(challengeId);
        return ResponseEntity.ok(Map.of("message", "Leaderboard rebuilt successfully"));
    }

    /**
     * Remove user from leaderboard (admin use)
     */
    @DeleteMapping("/challenge/{challengeId}/user/{userId}")
    @Operation(summary = "Remove user from leaderboard", 
               description = "Removes a user from the challenge leaderboard (admin use)")
    public ResponseEntity<Map<String, String>> removeFromLeaderboard(
            @PathVariable @Parameter(description = "Challenge ID") Long challengeId,
            @PathVariable @Parameter(description = "User ID") Long userId) {
        
        logger.info("DELETE /api/v1/leaderboard/challenge/{}/user/{}", challengeId, userId);
        
        leaderboardService.removeFromLeaderboard(challengeId, userId);
        return ResponseEntity.ok(Map.of("message", "User removed from leaderboard successfully"));
    }
}
