package com.ironside.challengesphere.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating a user's leaderboard score
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateLeaderboardScoreRequest {
    
    private String challengeId;
    
    private String userId;
    
    /**
     * Score to add (can be negative for deductions)
     */
    private Double score;
    
    /**
     * If true, sets the score directly instead of incrementing
     */
    @Builder.Default
    private Boolean setAbsolute = false;
    
    /**
     * Reason for the score update (for audit logging)
     */
    private String reason;
}
