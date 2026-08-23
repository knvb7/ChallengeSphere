package com.ironside.challengesphere.service.impl;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ironside.challengesphere.api.request.GetFeedRequest;
import com.ironside.challengesphere.api.response.FeedItemResponse;
import com.ironside.challengesphere.api.response.FeedResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FeedTimelineRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.FeedService;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Implementation of FeedService
 * Provides personalized feeds with caching support
 */
@Service
public class FeedServiceImpl implements FeedService {

    private static final Logger logger = LogManager.getLogger(FeedServiceImpl.class);
    private static final int MAX_LIMIT = 100;
    private static final int MAX_TIMELINE_CANDIDATES_PER_REQUEST = 500;
    private static final String CURSOR_AS_OF = "asOf";

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private FeedTimelineRepository feedTimelineRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private GloryRepository gloryRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private SubmissionMediaKeyPolicy submissionMediaKeyPolicy;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getFeed(GetFeedRequest request) throws ChallengeSphereException {
        int limit = normalizeLimit(request.getLimit());
        logger.info("Getting feed for userId: {}, type: {}, limit: {}",
                request.getUserId(), request.getFeedType(), limit);

        return switch (request.getFeedType()) {
            case FOLLOWING -> getFollowingFeed(request.getUserId(), limit, request.getCursor());
            case TRENDING -> getTrendingFeed(request.getUserId(), limit, request.getCursor());
            case EXPLORE -> getExploreFeed(request.getUserId(), limit, request.getCursor());
        };
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getFollowingFeed(String userId, int limit, String cursor) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit);
        String queryBinding = feedBinding("FOLLOWING", userId, userId, boundedLimit)
                + "|paging=durable-timeline-lek-v3";
        logger.info("Getting following feed for userId: {}, limit: {}", userId, boundedLimit);

        if (userId == null || userId.isBlank()) {
            throw new ChallengeSphereException("User ID is required for following feed", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            if (!userRepository.existsById(userId)) {
                throw new ChallengeSphereException("User not found: " + userId, HttpStatus.NOT_FOUND, logger);
            }
            Map<String, AttributeValue> continuation = decodeCursorKey(cursor, queryBinding);
            List<Submission> visibleSubmissions = new ArrayList<>(boundedLimit);
            int inspectedCandidates = 0;
            while (visibleSubmissions.size() < boundedLimit
                    && inspectedCandidates < MAX_TIMELINE_CANDIDATES_PER_REQUEST) {
                int queryLimit = Math.min(
                        boundedLimit - visibleSubmissions.size(),
                        MAX_TIMELINE_CANDIDATES_PER_REQUEST - inspectedCandidates
                );
                FeedTimelineRepository.TimelinePage timelinePage = feedTimelineRepository.findPage(
                        userId,
                        queryLimit,
                        continuation
                );
                inspectedCandidates += timelinePage.entries().size();
                List<String> submissionIds = timelinePage.entries().stream()
                        .map(FeedTimelineRepository.TimelineEntry::submissionId)
                        .toList();
                visibleSubmissions.addAll(
                        submissionRepository.findVisibleFollowingTimelineSubmissions(
                                userId,
                                submissionIds
                        )
                );
                continuation = timelinePage.lastEvaluatedKey();
                if (!timelinePage.hasMore()) {
                    break;
                }
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(visibleSubmissions, userId);
            boolean hasMore = !continuation.isEmpty();
            logger.info("Returning {} following feed items for userId={}", feedItems.size(), userId);
            return FeedResponse.create(
                    feedItems,
                    encodeCursor(queryBinding, continuation),
                    hasMore,
                    "FOLLOWING"
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting following feed for userId {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get following feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getConnectionsFeed(String userId, int limit, String cursor) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit);
        String queryBinding = feedBinding("CONNECTIONS", userId, userId, boundedLimit)
                + "|paging=durable-mutual-timeline-lek-v3";
        logger.info("Getting connections feed for userId: {}, limit: {}", userId, boundedLimit);

        if (userId == null || userId.isBlank()) {
            throw new ChallengeSphereException("User ID is required for connections feed", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            if (!userRepository.existsById(userId)) {
                throw new ChallengeSphereException("User not found: " + userId, HttpStatus.NOT_FOUND, logger);
            }
            Map<String, AttributeValue> continuation = decodeCursorKey(cursor, queryBinding);
            List<Submission> mutualSubmissions = new ArrayList<>(boundedLimit);
            int inspectedCandidates = 0;
            while (mutualSubmissions.size() < boundedLimit
                    && inspectedCandidates < MAX_TIMELINE_CANDIDATES_PER_REQUEST) {
                int queryLimit = Math.min(
                        boundedLimit - mutualSubmissions.size(),
                        MAX_TIMELINE_CANDIDATES_PER_REQUEST - inspectedCandidates
                );
                FeedTimelineRepository.TimelinePage timelinePage = feedTimelineRepository.findPage(
                        userId,
                        queryLimit,
                        continuation
                );
                inspectedCandidates += timelinePage.entries().size();
                List<String> submissionIds = timelinePage.entries().stream()
                        .map(FeedTimelineRepository.TimelineEntry::submissionId)
                        .toList();
                List<Submission> visible = submissionRepository
                        .findVisibleFollowingTimelineSubmissions(userId, submissionIds);
                Set<String> candidateAuthors = visible.stream()
                        .map(Submission::getUserId)
                        .filter(authorId -> !userId.equals(authorId))
                        .collect(Collectors.toSet());
                Set<String> reciprocalFollowers = followRepository.findActiveFollowerCandidateIds(
                        userId,
                        candidateAuthors
                );
                visible.stream()
                        .filter(submission -> userId.equals(submission.getUserId())
                                || reciprocalFollowers.contains(submission.getUserId()))
                        .forEach(mutualSubmissions::add);
                continuation = timelinePage.lastEvaluatedKey();
                if (!timelinePage.hasMore()) {
                    break;
                }
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(mutualSubmissions, userId);
            boolean hasMore = !continuation.isEmpty();
            logger.info("Returning {} connections feed items for userId={}", feedItems.size(), userId);
            return FeedResponse.create(
                    feedItems,
                    encodeCursor(queryBinding, continuation),
                    hasMore,
                    "CONNECTIONS"
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting connections feed for userId {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get connections feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getTrendingFeed(String currentUserId, int limit, String cursor) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit);
        String queryBinding = feedBinding("TRENDING", null, currentUserId, boundedLimit)
                + "|paging=weekly-shard-merge-v2";
        logger.info("Getting trending feed, limit: {}", boundedLimit);

        try {
            if (currentUserId == null) {
                return FeedResponse.empty("TRENDING");
            }
            DynamoDbCursorCodec.CursorState cursorState = decodeCursorState(cursor, queryBinding);
            Instant asOf = cursorAsOf(cursorState);
            SubmissionRepository.ShardedSubmissionPage submissionsPage =
                    submissionRepository.findPublicFeedPage(
                    currentUserId,
                    SubmissionRepository.PublicFeedOrder.TRENDING,
                    asOf,
                    boundedLimit,
                    cursorState.shardState()
            );
            List<FeedItemResponse> feedItems = enrichSubmissions(submissionsPage.items(), currentUserId);
            boolean hasMore = submissionsPage.hasMore();
            logger.info("Returning {} trending items", feedItems.size());
            return FeedResponse.create(
                    feedItems,
                    encodeShardCursor(
                            queryBinding,
                            Map.of(CURSOR_AS_OF, AttributeValue.fromN(Long.toString(asOf.getEpochSecond()))),
                            submissionsPage.shardState(),
                            hasMore
                    ),
                    hasMore,
                    "TRENDING"
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting trending feed: {}", e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get trending feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getExploreFeed(String currentUserId, int limit, String cursor) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit);
        String queryBinding = feedBinding("EXPLORE", null, currentUserId, boundedLimit)
                + "|paging=shard-merge-v2";
        logger.info("Getting explore feed, limit: {}", boundedLimit);

        try {
            if (currentUserId == null) {
                return FeedResponse.empty("EXPLORE");
            }
            DynamoDbCursorCodec.CursorState cursorState = decodeCursorState(cursor, queryBinding);
            if (!cursorState.lastEvaluatedKey().isEmpty()) {
                throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
            }
            SubmissionRepository.ShardedSubmissionPage submissionsPage =
                    submissionRepository.findPublicFeedPage(
                    currentUserId,
                    SubmissionRepository.PublicFeedOrder.EXPLORE,
                    Instant.now(),
                    boundedLimit,
                    cursorState.shardState()
            );
            List<FeedItemResponse> feedItems = enrichSubmissions(submissionsPage.items(), currentUserId);
            boolean hasMore = submissionsPage.hasMore();
            logger.info("Returning {} explore items", feedItems.size());
            return FeedResponse.create(
                    feedItems,
                    encodeShardCursor(
                            queryBinding,
                            Map.of(),
                            submissionsPage.shardState(),
                            hasMore
                    ),
                    hasMore,
                    "EXPLORE"
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting explore feed: {}", e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get explore feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getChallengeFeed(String challengeId, String currentUserId, int limit, String cursor)
            throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit);
        String queryBinding = feedBinding("CHALLENGE", challengeId, currentUserId, boundedLimit)
                + "|paging=lek-v2";
        logger.info("Getting challenge feed for challengeId: {}, limit: {}", challengeId, boundedLimit);

        if (challengeId == null || challengeId.isBlank()) {
            throw new ChallengeSphereException("Challenge ID is required", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            // Validate challenge exists
            if (!challengeRepository.existsById(challengeId)) {
                throw new ChallengeSphereException("Challenge not found: " + challengeId, HttpStatus.NOT_FOUND, logger);
            }

            if (currentUserId == null) {
                return FeedResponse.empty("CHALLENGE");
            }
            Map<String, AttributeValue> exclusiveStartKey = decodeCursorKey(cursor, queryBinding);
            DynamoPage<Submission> submissions = submissionRepository.findVisibleByChallengePage(
                    challengeId,
                    null,
                    currentUserId,
                    "newest",
                    null,
                    boundedLimit,
                    exclusiveStartKey
            );

            if (submissions.items().isEmpty()) {
                logger.debug("No submissions found for challengeId: {}", challengeId);
                return FeedResponse.empty("CHALLENGE");
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(submissions.items(), currentUserId);
            boolean hasMore = submissions.hasMore();
            return FeedResponse.create(
                    feedItems,
                    encodeCursor(queryBinding, submissions.lastEvaluatedKey()),
                    hasMore,
                    "CHALLENGE"
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting challenge feed for challengeId {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get challenge feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getUserFeed(String targetUserId, String currentUserId, int limit, String cursor)
            throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit);
        String queryBinding = feedBinding("USER", targetUserId, currentUserId, boundedLimit)
                + "|paging=lek-v2";
        logger.info("Getting user feed for targetUserId: {}, limit: {}", targetUserId, boundedLimit);

        if (targetUserId == null || targetUserId.isBlank()) {
            throw new ChallengeSphereException("Target user ID is required", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            boolean isOwnProfile = targetUserId.equals(currentUserId);

            // Validate user exists
            if (!userRepository.existsById(targetUserId)) {
                throw new ChallengeSphereException("User not found: " + targetUserId, HttpStatus.NOT_FOUND, logger);
            }

            if (!isOwnProfile && userBlockRepository.existsActiveBlockBetween(currentUserId, targetUserId)) {
                throw new ChallengeSphereException(
                        "Not authorized to view this user's posts",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            if (!isOwnProfile && !followRepository.existsActiveMutualFollow(currentUserId, targetUserId)) {
                throw new ChallengeSphereException(
                        "You can only view this user's posts if you follow each other",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }
            Map<String, AttributeValue> exclusiveStartKey = decodeCursorKey(cursor, queryBinding);
            DynamoPage<Submission> submissions = submissionRepository.findActiveByUserPage(
                    targetUserId,
                    currentUserId,
                    !isOwnProfile,
                    boundedLimit,
                    exclusiveStartKey
            );

            if (submissions.items().isEmpty()) {
                logger.debug("No submissions found for targetUserId: {}", targetUserId);
                return FeedResponse.empty("USER");
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(submissions.items(), currentUserId);
            boolean hasMore = submissions.hasMore();
            return FeedResponse.create(
                    feedItems,
                    encodeCursor(queryBinding, submissions.lastEvaluatedKey()),
                    hasMore,
                    "USER"
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting user feed for targetUserId {}: {}", targetUserId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get user feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private String feedBinding(String feedType, String resourceId, String viewerId, int limit) {
        return "feed:v1:type=" + feedType
                + "|resource=" + Objects.toString(resourceId, "")
                + "|viewer=" + Objects.toString(viewerId, "")
                + "|limit=" + limit;
    }

    private Map<String, AttributeValue> decodeCursorKey(String cursor, String queryBinding)
            throws ChallengeSphereException {
        return decodeCursorState(cursor, queryBinding).lastEvaluatedKey();
    }

    private DynamoDbCursorCodec.CursorState decodeCursorState(
            String cursor,
            String queryBinding
    ) throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return new DynamoDbCursorCodec.CursorState(Map.of(), Map.of());
        }
        try {
            return cursorCodec.decode(cursor, queryBinding);
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private Instant cursorAsOf(DynamoDbCursorCodec.CursorState state)
            throws ChallengeSphereException {
        if (state.lastEvaluatedKey().isEmpty()) {
            return Instant.now();
        }
        if (state.lastEvaluatedKey().size() != 1
                || !state.lastEvaluatedKey().containsKey(CURSOR_AS_OF)) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
        try {
            AttributeValue encoded = state.lastEvaluatedKey().get(CURSOR_AS_OF);
            Instant asOf = Instant.ofEpochSecond(Long.parseLong(encoded.n()));
            if (asOf.isAfter(Instant.now().plusSeconds(60))) {
                throw new IllegalArgumentException("Cursor as-of time is in the future");
            }
            return asOf;
        } catch (RuntimeException invalid) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(String queryBinding, Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) return null;
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(lastEvaluatedKey)
        );
    }

    private String encodeShardCursor(
            String queryBinding,
            Map<String, AttributeValue> metadata,
            Map<String, Map<String, AttributeValue>> shardState,
            boolean hasMore
    ) {
        if (!hasMore) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(metadata, shardState)
        );
    }

    // ==================== HELPER METHODS ====================

    /**
     * Enrich submissions with user and challenge information
     * Uses batch queries to avoid N+1 problems
     */
    private List<FeedItemResponse> enrichSubmissions(List<Submission> submissions, String currentUserId) {
        if (submissions.isEmpty()) {
            return List.of();
        }

        // Collect unique user IDs and challenge IDs
        Set<String> userIds = submissions.stream()
                .map(Submission::getUserId)
                .collect(Collectors.toSet());
        Set<String> challengeIds = submissions.stream()
                .map(Submission::getChallengeId)
                .collect(Collectors.toSet());
        Set<String> submissionIds = submissions.stream()
                .map(Submission::getId)
                .collect(Collectors.toSet());

        // Batch fetch users and challenges
        Map<String, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<String, Challenge> challengeMap = challengeRepository.findAllById(challengeIds).stream()
                .collect(Collectors.toMap(Challenge::getId, c -> c));
        Set<String> activeChallengeMemberships = currentUserId == null
                ? Set.of()
                : userChallengeRepository.findActiveMembershipChallengeIds(
                        currentUserId,
                        challengeIds
                );
        Set<String> blockedUserIds = currentUserId == null
                ? Set.of()
                : userBlockRepository.findActiveBlockedCounterpartIds(
                        currentUserId,
                        userIds
                );

        // Batch check if current user has gloried these submissions
        Map<String, Boolean> gloryMap = new HashMap<>();
        Map<String, Boolean> ungloryMap = new HashMap<>();
        if (currentUserId != null) {
            gloryRepository.findReactionStates(submissionIds, currentUserId)
                    .forEach((submissionId, state) -> {
                        gloryMap.put(submissionId, state.gloried());
                        ungloryMap.put(submissionId, state.ungloried());
                    });
        }

        // Build feed items
        List<FeedItemResponse> feedItems = new ArrayList<>();
        for (Submission submission : submissions) {
            User user = userMap.get(submission.getUserId());
            Challenge challenge = challengeMap.get(submission.getChallengeId());

            if (user == null || challenge == null) {
                logger.warn("Missing user or challenge data for submission {}", submission.getId());
                continue;
            }
            if (!Boolean.TRUE.equals(challenge.getActive())) {
                continue;
            }
            if (!Boolean.TRUE.equals(submission.getActive())) {
                continue;
            }
            boolean isAuthor = currentUserId != null && currentUserId.equals(submission.getUserId());
            if (!isAuthor && Boolean.TRUE.equals(challenge.getIsPrivate())
                    && !activeChallengeMemberships.contains(challenge.getId())) {
                continue;
            }
            if (!isAuthor && !Boolean.TRUE.equals(challenge.getIsPrivate())
                    && Boolean.TRUE.equals(submission.getIsPrivate())) {
                continue;
            }
            if (currentUserId != null && !currentUserId.equals(user.getId())
                    && blockedUserIds.contains(user.getId())) {
                continue;
            }

            Boolean isGloried = currentUserId != null ? gloryMap.getOrDefault(submission.getId(), false) : null;
            Boolean isUngloried = currentUserId != null ? ungloryMap.getOrDefault(submission.getId(), false) : null;

            FeedItemResponse item = FeedItemResponse.fromSubmission(
                    submission,
                    user.getUsername(),
                    mediaUrlService.toReadableProfilePhoto(user.getId(), user.getProfilePicUrl()),
                    challenge.getTitle(),
                    isGloried,
                    isUngloried
            );
            List<String> safeMediaKeys = submissionMediaKeyPolicy.filterOwnedKeysForRead(
                    submission.getMediaUrls(),
                    submissionMediaOwnerIds(submission.getUserId(), user));
            item.setMediaUrls(mediaUrlService.toReadableMediaUrlList(
                    String.join(",", safeMediaKeys)));
            feedItems.add(item);
        }

        return feedItems;
    }

    private Set<String> submissionMediaOwnerIds(String currentOwnerId, User owner) {
        if (currentOwnerId == null || currentOwnerId.isBlank()) {
            return Set.of();
        }
        Set<String> allowedOwnerIds = new HashSet<>();
        allowedOwnerIds.add(currentOwnerId);
        if (owner != null
                && currentOwnerId.equals(owner.getId())
                && isNumericLegacyUserId(owner.getLegacyId())) {
            allowedOwnerIds.add(owner.getLegacyId());
        }
        return Set.copyOf(allowedOwnerIds);
    }

    private static boolean isNumericLegacyUserId(String userId) {
        return userId != null
                && !userId.isBlank()
                && userId.chars().allMatch(character -> character >= '0' && character <= '9');
    }
}
