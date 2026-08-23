package com.ironside.challengesphere.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for fetching leaderboard data
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GetLeaderboardRequest {
    
    private String challengeId;

    /** Authenticated user requesting access to this leaderboard. */
    private String viewerUserId;
    
    /**
     * Number of top entries to return (default: 10, max: 100)
     */
    @Builder.Default
    private Integer limit = 10;

    /** Opaque continuation cursor returned by the previous leaderboard page. */
    private String cursor;
    
    /**
     * Optional: Get rank for a specific user
     */
    private String userId;
    
    /**
     * Leaderboard type: SUBMISSIONS, GLORIES, STREAK, OVERALL
     */
    @Builder.Default
    private LeaderboardType type = LeaderboardType.OVERALL;
    
    public enum LeaderboardType {
        SUBMISSIONS,  // Ranked by number of submissions
        GLORIES,      // Ranked by total glories received
        STREAK,       // Ranked by current streak
        OVERALL       // Combined score
    }
    
    public int getLimit() {
        if (limit == null || limit <= 0) {
            return 10;
        }
        return Math.min(limit, 100); // Cap at 100
    }
}
