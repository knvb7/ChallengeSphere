package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Request for getting user feed
 */
@Data
public class GetFeedRequest {

    private Long userId;

    @Min(value = 1, message = "Page must be at least 1")
    private int page = 1;

    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 50, message = "Page size cannot exceed 50")
    private int pageSize = 20;

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
