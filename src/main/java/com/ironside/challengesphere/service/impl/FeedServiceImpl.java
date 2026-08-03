package com.ironside.challengesphere.service.impl;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.FeedService;

/**
 * Implementation of FeedService
 * Provides personalized feeds with caching support
 */
@Service
public class FeedServiceImpl implements FeedService {

    private static final Logger logger = LogManager.getLogger(FeedServiceImpl.class);
    private static final int TIMELINE_REBUILD_SIZE = 500;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private GloryRepository gloryRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private SubmissionMediaKeyPolicy submissionMediaKeyPolicy;

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getFeed(GetFeedRequest request) throws ChallengeSphereException {
        logger.info("Getting feed for userId: {}, type: {}, page: {}", 
                request.getUserId(), request.getFeedType(), request.getPage());

        return switch (request.getFeedType()) {
            case FOLLOWING -> getFollowingFeed(request.getUserId(), request.getPage(), request.getPageSize());
            case TRENDING -> getTrendingFeed(request.getPage(), request.getPageSize(), request.getUserId());
            case EXPLORE -> getExploreFeed(request.getPage(), request.getPageSize(), request.getUserId());
        };
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getFollowingFeed(Long userId, int page, int pageSize) throws ChallengeSphereException {
        logger.info("Getting following feed for userId: {}, page: {}, pageSize: {}", userId, page, pageSize);

        if (userId == null) {
            throw new ChallengeSphereException("User ID is required for following feed", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            if (!userRepository.existsById(userId)) {
                throw new ChallengeSphereException("User not found: " + userId, HttpStatus.NOT_FOUND, logger);
            }

            int normalizedPage = Math.max(1, page);
            int offset = (normalizedPage - 1) * pageSize;
            if (cacheService.timelineExists(userId)) {
                List<Long> cachedSubmissionIds = cacheService.getTimelineIds(userId, offset, pageSize);
                long totalItems = cacheService.getTimelineSize(userId);
                if (!cachedSubmissionIds.isEmpty()) {
                    List<Submission> cachedSubmissions = hydrateTimelineSubmissions(cachedSubmissionIds);
                    List<FeedItemResponse> cachedItems = enrichSubmissions(cachedSubmissions, userId);
                    return FeedResponse.create(cachedItems, page, pageSize, totalItems, "FOLLOWING");
                }
                return totalItems > 0
                        ? FeedResponse.create(List.of(), page, pageSize, totalItems, "FOLLOWING")
                        : FeedResponse.empty(page, pageSize, "FOLLOWING");
            }

            Pageable pageable = PageRequest.of(normalizedPage - 1, pageSize);
            Page<Submission> submissionsPage = submissionRepository.findFeedForUser(userId, pageable);

            if (submissionsPage.isEmpty()) {
                logger.debug("No submissions found in following feed for userId: {}", userId);
                return FeedResponse.empty(page, pageSize, "FOLLOWING");
            }

            Pageable rebuildPageable = PageRequest.of(0, TIMELINE_REBUILD_SIZE);
            Page<Submission> timelinePage = submissionRepository.findFeedForUser(userId, rebuildPageable);
            List<Long> timelineSubmissionIds = timelinePage.getContent().stream()
                    .map(Submission::getId)
                    .toList();
            List<Double> timelineScores = timelinePage.getContent().stream()
                    .map(submission -> {
                        if (submission.getCreatedAt() != null) {
                            return (double) submission.getCreatedAt().getTime();
                        }
                        return (double) System.currentTimeMillis();
                    })
                    .toList();
            cacheService.rebuildTimeline(userId, timelineSubmissionIds, timelineScores);

            List<FeedItemResponse> feedItems = enrichSubmissions(submissionsPage.getContent(), userId);
            logger.info("Returning {} following feed items for userId={}, page={}",
                    feedItems.size(), userId, page);
            FeedResponse response = FeedResponse.create(feedItems, page, pageSize, submissionsPage.getTotalElements(), "FOLLOWING");
            return response;

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting following feed for userId {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get following feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getConnectionsFeed(Long userId, int page, int pageSize) throws ChallengeSphereException {
        logger.info("Getting connections feed for userId: {}, page: {}, pageSize: {}", userId, page, pageSize);

        if (userId == null) {
            throw new ChallengeSphereException("User ID is required for connections feed", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            if (!userRepository.existsById(userId)) {
                throw new ChallengeSphereException("User not found: " + userId, HttpStatus.NOT_FOUND, logger);
            }

            int normalizedPage = Math.max(1, page);
            Pageable pageable = PageRequest.of(normalizedPage - 1, pageSize);
            Page<Submission> submissionsPage = submissionRepository.findConnectionsFeedForUser(userId, pageable);

            if (submissionsPage.isEmpty()) {
                logger.debug("No submissions found in connections feed for userId: {}", userId);
                return FeedResponse.empty(page, pageSize, "CONNECTIONS");
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(submissionsPage.getContent(), userId);
            logger.info("Returning {} connections feed items for userId={}, page={}",
                    feedItems.size(), userId, page);
            return FeedResponse.create(
                    feedItems,
                    page,
                    pageSize,
                    submissionsPage.getTotalElements(),
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
    public FeedResponse getTrendingFeed(int page, int pageSize, Long currentUserId) throws ChallengeSphereException {
        logger.info("Getting trending feed, page: {}, pageSize: {}", page, pageSize);

        try {
            if (currentUserId == null) {
                return FeedResponse.empty(page, pageSize, "TRENDING");
            }

            Pageable pageable = PageRequest.of(page - 1, pageSize);
            Page<Submission> submissionsPage = submissionRepository.findTrendingSubmissions(currentUserId, pageable);

            if (submissionsPage.isEmpty()) {
                logger.debug("No trending submissions found");
                return FeedResponse.empty(page, pageSize, "TRENDING");
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(submissionsPage.getContent(), currentUserId);

            logger.info("Returning {} trending items, page {}/{}",
                    feedItems.size(), page, submissionsPage.getTotalPages());

            FeedResponse response = FeedResponse.create(feedItems, page, pageSize,
                    submissionsPage.getTotalElements(), "TRENDING");

            return response;

        } catch (Exception e) {
            logger.error("Error getting trending feed: {}", e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get trending feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getExploreFeed(int page, int pageSize, Long currentUserId) throws ChallengeSphereException {
        logger.info("Getting explore feed, page: {}, pageSize: {}", page, pageSize);

        try {
            if (currentUserId == null) {
                return FeedResponse.empty(page, pageSize, "EXPLORE");
            }

            Pageable pageable = PageRequest.of(page - 1, pageSize);
            Page<Submission> submissionsPage = submissionRepository.findPublicSubmissions(currentUserId, pageable);

            if (submissionsPage.isEmpty()) {
                logger.debug("No public submissions found");
                return FeedResponse.empty(page, pageSize, "EXPLORE");
            }

            List<FeedItemResponse> feedItems = enrichSubmissions(submissionsPage.getContent(), currentUserId);

            logger.info("Returning {} explore items, page {}/{}",
                    feedItems.size(), page, submissionsPage.getTotalPages());

            FeedResponse response = FeedResponse.create(feedItems, page, pageSize,
                    submissionsPage.getTotalElements(), "EXPLORE");

            return response;

        } catch (Exception e) {
            logger.error("Error getting explore feed: {}", e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get explore feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getChallengeFeed(Long challengeId, int page, int pageSize, Long currentUserId) 
            throws ChallengeSphereException {
        logger.info("Getting challenge feed for challengeId: {}, page: {}", challengeId, page);

        if (challengeId == null) {
            throw new ChallengeSphereException("Challenge ID is required", HttpStatus.BAD_REQUEST, logger);
        }

        try {
            // Validate challenge exists
            if (!challengeRepository.existsById(challengeId)) {
                throw new ChallengeSphereException("Challenge not found: " + challengeId, HttpStatus.NOT_FOUND, logger);
            }

            if (currentUserId == null) {
                return FeedResponse.empty(page, pageSize, "CHALLENGE");
            }

            List<Submission> submissions = submissionRepository.findPublicActiveByChallengeId(challengeId, currentUserId);

            if (submissions.isEmpty()) {
                logger.debug("No submissions found for challengeId: {}", challengeId);
                return FeedResponse.empty(page, pageSize, "CHALLENGE");
            }

            // Manual pagination
            int totalItems = submissions.size();
            int offset = (page - 1) * pageSize;
            List<Submission> paginatedSubmissions = submissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            List<FeedItemResponse> feedItems = enrichSubmissions(paginatedSubmissions, currentUserId);

            FeedResponse response = FeedResponse.create(feedItems, page, pageSize, totalItems, "CHALLENGE");

            return response;

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting challenge feed for challengeId {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get challenge feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FeedResponse getUserFeed(Long targetUserId, int page, int pageSize, Long currentUserId) 
            throws ChallengeSphereException {
        logger.info("Getting user feed for targetUserId: {}, page: {}", targetUserId, page);

        if (targetUserId == null) {
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

            // Get submissions - if viewing own profile, show all; otherwise only public
            List<Submission> submissions;

            if (isOwnProfile) {
                submissions = submissionRepository.findActiveByUserId(targetUserId);
            } else {
                submissions = submissionRepository.findPublicByUserId(targetUserId);
            }

            if (submissions.isEmpty()) {
                logger.debug("No submissions found for targetUserId: {}", targetUserId);
                return FeedResponse.empty(page, pageSize, "USER");
            }

            // Manual pagination
            int totalItems = submissions.size();
            int offset = (page - 1) * pageSize;
            List<Submission> paginatedSubmissions = submissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            List<FeedItemResponse> feedItems = enrichSubmissions(paginatedSubmissions, currentUserId);

            FeedResponse response = FeedResponse.create(feedItems, page, pageSize, totalItems, "USER");

            return response;

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting user feed for targetUserId {}: {}", targetUserId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to get user feed", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    // ==================== HELPER METHODS ====================

    private List<Submission> hydrateTimelineSubmissions(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Submission> submissionsById = submissionRepository.findAllById(submissionIds).stream()
                .collect(Collectors.toMap(Submission::getId, submission -> submission));
        return submissionIds.stream()
                .map(submissionsById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Enrich submissions with user and challenge information
     * Uses batch queries to avoid N+1 problems
     */
    private List<FeedItemResponse> enrichSubmissions(List<Submission> submissions, Long currentUserId) {
        if (submissions.isEmpty()) {
            return List.of();
        }

        // Collect unique user IDs and challenge IDs
        Set<Long> userIds = submissions.stream()
                .map(Submission::getUserId)
                .collect(Collectors.toSet());
        Set<Long> challengeIds = submissions.stream()
                .map(Submission::getChallengeId)
                .collect(Collectors.toSet());
        Set<Long> submissionIds = submissions.stream()
                .map(Submission::getId)
                .collect(Collectors.toSet());

        // Batch fetch users and challenges
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, Challenge> challengeMap = challengeRepository.findAllById(challengeIds).stream()
                .collect(Collectors.toMap(Challenge::getId, c -> c));
        Set<Long> activeChallengeMemberships = currentUserId == null
                ? Set.of()
                : userChallengeRepository.findActiveByUserId(currentUserId).stream()
                .map(UserChallenge::getChallengeId)
                .collect(Collectors.toSet());

        // Batch check if current user has gloried these submissions
        Map<Long, Boolean> gloryMap = new HashMap<>();
        Map<Long, Boolean> ungloryMap = new HashMap<>();
        if (currentUserId != null) {
            for (Long submissionId : submissionIds) {
                gloryMap.put(submissionId, gloryRepository.existsBySubmissionIdAndUserId(submissionId, currentUserId));
                ungloryMap.put(submissionId, gloryRepository.existsUngloriedBySubmissionIdAndUserId(submissionId, currentUserId));
            }
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
                    && userBlockRepository.existsActiveBlockBetween(currentUserId, user.getId())) {
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
                    submission.getUserId(), submission.getMediaUrls());
            item.setMediaUrls(mediaUrlService.toReadableMediaUrlList(
                    String.join(",", safeMediaKeys)));
            feedItems.add(item);
        }

        return feedItems;
    }
}
