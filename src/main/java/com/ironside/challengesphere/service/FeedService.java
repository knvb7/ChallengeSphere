package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.GetFeedRequest;
import com.ironside.challengesphere.api.response.FeedResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;

/**
 * Service interface for Feed operations
 * Provides personalized feeds based on user preferences and social connections
 */
public interface FeedService {

    /**
     * Get personalized feed for a user
     * @param request Contains userId, pagination, and feed type
     * @return FeedResponse with paginated feed items
     */
    FeedResponse getFeed(GetFeedRequest request) throws ChallengeSphereException;

    /**
     * Get feed of submissions from users that the current user follows
     * @param userId Current user's ID
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @return FeedResponse with submissions from followed users
     */
    FeedResponse getFollowingFeed(Long userId, int page, int pageSize) throws ChallengeSphereException;

    /**
     * Get activity from the current user's social connections (followers and following).
     * Public submissions are visible from either relationship direction. Private-challenge
     * submissions are returned only when the current user is an active participant in the
     * same challenge.
     *
     * @param userId Current user's ID
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @return FeedResponse with privacy-filtered connection activity
     */
    FeedResponse getConnectionsFeed(Long userId, int page, int pageSize) throws ChallengeSphereException;

    /**
     * Get trending feed - popular submissions based on engagement
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @param currentUserId Optional - current user ID for personalization (glory status)
     * @return FeedResponse with trending submissions
     */
    FeedResponse getTrendingFeed(int page, int pageSize, Long currentUserId) throws ChallengeSphereException;

    /**
     * Get explore feed - all public submissions sorted by recency
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @param currentUserId Optional - current user ID for personalization (glory status)
     * @return FeedResponse with public submissions
     */
    FeedResponse getExploreFeed(int page, int pageSize, Long currentUserId) throws ChallengeSphereException;

    /**
     * Get feed for a specific challenge
     * @param challengeId Challenge ID
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @param currentUserId Optional - current user ID for personalization
     * @return FeedResponse with submissions for the challenge
     */
    FeedResponse getChallengeFeed(Long challengeId, int page, int pageSize, Long currentUserId) throws ChallengeSphereException;

    /**
     * Get feed for a specific user's submissions
     * @param targetUserId User whose submissions to fetch
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @param currentUserId Current user ID (to check if viewing own profile vs others)
     * @return FeedResponse with user's submissions
     */
    FeedResponse getUserFeed(Long targetUserId, int page, int pageSize, Long currentUserId) throws ChallengeSphereException;
}
