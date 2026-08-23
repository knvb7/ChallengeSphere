package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Request for getting user feed
 */
@Data
public class GetFeedRequest {

    private String userId;

    private String cursor;

    @Min(value = 1, message = "Limit must be at least 1")
    @Max(value = 50, message = "Limit cannot exceed 50")
    private int limit = 20;

    /**
     * Feed type: FOLLOWING, TRENDING, EXPLORE
     * FOLLOWING - submissions from users the current user follows
     * TRENDING - popular submissions based on engagement
     * EXPLORE - all public submissions sorted by recency
     */
    private FeedType feedType = FeedType.FOLLOWING;

    public enum FeedType {
        FOLLOWING,  // Submissions from followed users
        TRENDING,   // Hot/popular submissions
        EXPLORE     // All public submissions
    }
}
