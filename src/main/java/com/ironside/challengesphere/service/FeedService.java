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
     * @param request Contains userId, cursor pagination, and feed type
     * @return FeedResponse with cursor-paginated feed items
     */
    FeedResponse getFeed(GetFeedRequest request) throws ChallengeSphereException;

    /**
     * Get feed of submissions from users that the current user follows
     * @param userId Current user's ID
     * @param limit Maximum number of items to return
     * @param cursor Opaque continuation cursor, or {@code null} for the first page
     * @return FeedResponse with submissions from followed users
     */
    FeedResponse getFollowingFeed(String userId, int limit, String cursor) throws ChallengeSphereException;

    /**
     * Get activity from the current user's social connections (followers and following).
     * Public submissions are visible from either relationship direction. Private-challenge
     * submissions are returned only when the current user is an active participant in the
     * same challenge.
     *
     * @param userId Current user's ID
     * @param limit Maximum number of items to return
     * @param cursor Opaque continuation cursor, or {@code null} for the first page
     * @return FeedResponse with privacy-filtered connection activity
     */
    FeedResponse getConnectionsFeed(String userId, int limit, String cursor) throws ChallengeSphereException;

    /**
     * Get trending feed - popular submissions based on engagement
     * @param currentUserId Optional - current user ID for personalization (glory status)
     * @param limit Maximum number of items to return
     * @param cursor Opaque continuation cursor, or {@code null} for the first page
     * @return FeedResponse with trending submissions
     */
    FeedResponse getTrendingFeed(String currentUserId, int limit, String cursor) throws ChallengeSphereException;

    /**
     * Get explore feed - all public submissions sorted by recency
     * @param currentUserId Optional - current user ID for personalization (glory status)
     * @param limit Maximum number of items to return
     * @param cursor Opaque continuation cursor, or {@code null} for the first page
     * @return FeedResponse with public submissions
     */
    FeedResponse getExploreFeed(String currentUserId, int limit, String cursor) throws ChallengeSphereException;

    /**
     * Get feed for a specific challenge
     * @param challengeId Challenge ID
     * @param currentUserId Optional - current user ID for personalization
     * @param limit Maximum number of items to return
     * @param cursor Opaque continuation cursor, or {@code null} for the first page
     * @return FeedResponse with submissions for the challenge
     */
    FeedResponse getChallengeFeed(
            String challengeId,
            String currentUserId,
            int limit,
            String cursor
    ) throws ChallengeSphereException;

    /**
     * Get feed for a specific user's submissions
     * @param targetUserId User whose submissions to fetch
     * @param currentUserId Current user ID (to check if viewing own profile vs others)
     * @param limit Maximum number of items to return
     * @param cursor Opaque continuation cursor, or {@code null} for the first page
     * @return FeedResponse with user's submissions
     */
    FeedResponse getUserFeed(
            String targetUserId,
            String currentUserId,
            int limit,
            String cursor
    ) throws ChallengeSphereException;
}
