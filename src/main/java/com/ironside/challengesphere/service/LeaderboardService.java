package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.GetLeaderboardRequest;
import com.ironside.challengesphere.api.request.UpdateLeaderboardScoreRequest;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

/**
 * Service interface for leaderboard operations.
 * Handles real-time leaderboard management with Redis caching.
 */
public interface LeaderboardService {
    
    /**
     * Get leaderboard for a challenge
     * @param request Contains challengeId, limit, optional userId for rank
     * @return LeaderboardResponse with ranked entries
     */
    LeaderboardResponse getLeaderboard(GetLeaderboardRequest request) throws ChallengeSphereException;
    
    /**
     * Get current user's rank in a challenge leaderboard
     * @param challengeId The challenge ID
     * @param userId The user ID
     * @return UserRankDTO with rank information
     */
    LeaderboardResponse.UserRankDTO getUserRank(Long challengeId, Long userId) throws ChallengeSphereException;
    
    /**
     * Update a user's score in the leaderboard
     * Used when user submits, receives glories, etc.
     * @param request Contains challengeId, userId, score, and reason
     */
    void updateScore(UpdateLeaderboardScoreRequest request) throws ChallengeSphereException;
    
    /**
     * Increment user's score (for events like submissions, receiving glories)
     * @param challengeId The challenge ID
     * @param userId The user ID
     * @param points Points to add
     * @param reason Reason for the score update
     */
    void incrementScore(Long challengeId, Long userId, double points, String reason);
    
    /**
     * Recalculate and rebuild leaderboard for a challenge from database
     * Use this for data consistency checks or after data migrations
     * @param challengeId The challenge ID
     */
    void rebuildLeaderboard(Long challengeId) throws ChallengeSphereException;
    
    /**
     * Remove a user from the leaderboard
     * @param challengeId The challenge ID
     * @param userId The user ID
     */
    void removeFromLeaderboard(Long challengeId, Long userId);
    
    /**
     * Initialize leaderboard for a user joining a challenge
     * @param challengeId The challenge ID
     * @param userId The user ID
     */
    void initializeUserScore(Long challengeId, Long userId);
}
