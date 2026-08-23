package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowRelationshipResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.PublicUserResponse;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.api.response.UserSearchResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserBlock;
import com.ironside.challengesphere.model.UserReport;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserReportRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.UserRepository.FollowTransition;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.UserService;
import com.ironside.challengesphere.util.AccountAgePolicy;
import com.ironside.challengesphere.util.UsernamePolicy;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class UserServiceImpl implements UserService {
    private static final Logger logger = LogManager.getLogger(UserServiceImpl.class);
    private static final String CURSOR_MODE = "mode";
    private static final String CURSOR_SEEN = "seen";
    private static final String SUGGESTION_MODE_INTERESTS = "interests";
    private static final String SUGGESTION_MODE_FALLBACK = "fallback";
    private static final String FALLBACK_SHARD = "fallback";
    private static final int MAX_USER_SEARCH_LIMIT = 50;
    private static final int MAX_SUGGESTION_LIMIT = 20;
    private static final int MAX_SOCIAL_LIMIT = 100;
    private static final int MAX_SOCIAL_CANDIDATES = 500;
    private static final int SOCIAL_QUERY_PAGE_SIZE = 100;
    private static final int MAX_DISCOVERY_INTERESTS = 20;
    private static final String USER_PAIR_LEASE_SCOPE = "USER_RELATIONSHIP";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserReportRepository userReportRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private ProfilePhotoService profilePhotoService;

    @Autowired
    private RedisCacheServiceImpl redisCacheServiceImpl;

    @Autowired
    private AuthTokenStore authTokenStore;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    @Autowired
    private ConditionalKeyLease conditionalKeyLease;

    @Autowired
    private FeedTimelineMaterializer feedTimelineMaterializer;

    @Override
    public long getTotalUserCount() {
        return userRepository.count();
    }

    @Override
    public User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException {
        String userId = getUserPayload.getUserId();
        String currentUserId = getUserPayload.getCurrentUserId();
        String emailId = getUserPayload.getEmailId();
        String phone = getUserPayload.getPhone();
        String username = getUserPayload.getUsername();
        logger.debug("Retrieving user profile");

        try {
            // 1. Fetch by userId — cache first, DB on miss or cache failure
            if (userId != null) {
                try {
                    Optional<User> cached = cacheService.getCachedUserProfile(userId, null, null, User.class);
                    if (cached.isPresent()) {
                        logger.info("Cache hit for userId: {}", userId);
                        stampLastLogin(cached.get());
                    return restrictProfileForViewer(cached.get(), currentUserId);
                    }
                } catch (Exception cacheEx) {
                    logger.warn("Cache lookup failed for userId={}, falling back to DB", userId);
                }
                logger.info("Fetching from DB for userId: {}", userId);
                Optional<User> user = userRepository.findById(userId);
                if (user.isPresent()) {
                    logger.info("User found by userId: {}", userId);
                    final User found = user.get();
                    stampLastLogin(found);
                    safeCacheUser(found);
                    return restrictProfileForViewer(found, currentUserId);
                }
            }

            // 2. Fetch by emailId from DB
            if (emailId != null && !emailId.trim().isEmpty()) {
                String emailKey = emailId.trim();
                Optional<User> user = userRepository.findByEmailId(emailKey);
                if (user.isPresent()) {
                    logger.debug("Account lookup matched email");
                    final User found = user.get();
                    stampLastLogin(found);
                    safeCacheUser(found);
                    return restrictProfileForViewer(found, currentUserId);
                }
            }

            // 3. Fetch by phone from DB
            if (phone != null && !phone.trim().isEmpty()) {
                String phoneKey = phone.trim();
                Optional<User> user = userRepository.findByPhone(phoneKey);
                if (user.isPresent()) {
                    logger.debug("Account lookup matched phone");
                    final User found = user.get();
                    stampLastLogin(found);
                    safeCacheUser(found);
                    return restrictProfileForViewer(found, currentUserId);
                }
            }

            // 4. Fetch by username from DB
            if (username != null && !username.trim().isEmpty()) {
                String usernameKey = username.trim();
                logger.debug("Fetching user by username");
                Optional<User> user = userRepository.findByUsername(usernameKey);
                if (user.isPresent()) {
                    logger.debug("User found by username");
                    final User found = user.get();
                    stampLastLogin(found);
                    safeCacheUser(found);
                    return restrictProfileForViewer(found, currentUserId);
                }
            }

            logger.debug("User lookup did not match an account");
        } catch (Exception e) {
            logger.error("User retrieval failed | exception={}", e.getClass().getSimpleName());
        }

        throw new ChallengeSphereException("User not found with provided parameters", HttpStatus.NOT_FOUND, logger);
    }

    @Override
    public UserSearchResponse searchUsers(String query, String currentUserId, Integer limit, String cursor)
            throws ChallengeSphereException {
        int boundedLimit = boundedLimit(limit, 10, MAX_USER_SEARCH_LIMIT);
        String normalizedQuery = normalizeUserSearchQuery(query);
        String queryBinding = "users:search:v2|query=" + normalizedQuery
                + "|viewer=" + cursorValue(currentUserId)
                + "|limit=" + boundedLimit;

        if (normalizedQuery.isEmpty()) {
            return UserSearchResponse.empty(normalizedQuery);
        }

        try {
            DynamoDbCursorCodec.CursorState cursorState = decodeOpaqueCursor(cursor, queryBinding);
            UserRepository.ShardedUserPage page = userRepository.searchDiscoverUsersPage(
                    normalizedQuery,
                    currentUserId,
                    boundedLimit,
                    cursorState.shardState()
            );
            List<User> visibleUsers = page.items().stream()
                    .map(this::restrictedProfile)
                    .map(this::withReadableProfilePicUrl)
                    .toList();

            return UserSearchResponse.of(
                    normalizedQuery,
                    visibleUsers,
                    encodeShardCursor(queryBinding, page.shardState(), page.hasMore()),
                    page.hasMore()
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("User search failed | exception={}", e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to search users",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public UserSearchResponse getSuggestedUsers(String currentUserId, Integer limit, String cursor)
            throws ChallengeSphereException {
        int boundedLimit = boundedLimit(limit, 10, MAX_SUGGESTION_LIMIT);

        try {
            User viewer = userRepository.findById(currentUserId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "User not found with id: " + currentUserId,
                            HttpStatus.NOT_FOUND,
                            logger
            ));
            String interestCsv = normalizeInterestsForQuery(viewer.getInterests());
            List<String> interests = interestCsv.isBlank()
                    ? List.of()
                    : java.util.Arrays.asList(interestCsv.split(","));
            String queryBinding = "users:suggestions:v2|viewer=" + currentUserId
                    + "|interests=" + interestCsv
                    + "|limit=" + boundedLimit;
            DynamoDbCursorCodec.CursorState cursorState = decodeOpaqueCursor(cursor, queryBinding);
            String mode = suggestionMode(cursorState, interests.isEmpty());
            boolean seenInterestSuggestion = cursorFlag(cursorState, CURSOR_SEEN);

            if (SUGGESTION_MODE_INTERESTS.equals(mode)) {
                UserRepository.ShardedUserPage page = userRepository.findSuggestedUsersPage(
                        currentUserId,
                        interests,
                        boundedLimit,
                        cursorState.shardState()
                );
                boolean seen = seenInterestSuggestion || !page.items().isEmpty();
                if (page.hasMore() || seen) {
                    return suggestedUsersResponse(
                            page.items(),
                            encodeSuggestionCursor(
                                    queryBinding,
                                    SUGGESTION_MODE_INTERESTS,
                                    seen,
                                    page.shardState(),
                                    page.hasMore()
                            ),
                            page.hasMore()
                    );
                }
                mode = SUGGESTION_MODE_FALLBACK;
                cursorState = new DynamoDbCursorCodec.CursorState(Map.of(), Map.of());
            }

            Map<String, AttributeValue> fallbackCursor = cursorState.shardState()
                    .getOrDefault(FALLBACK_SHARD, Map.of());
            DynamoPage<User> page = userRepository.findFallbackSuggestedUsersPage(
                    currentUserId,
                    boundedLimit,
                    fallbackCursor
            );
            Map<String, Map<String, AttributeValue>> fallbackState = Map.of(
                    FALLBACK_SHARD,
                    page.lastEvaluatedKey()
            );
            return suggestedUsersResponse(
                    page.items(),
                    encodeSuggestionCursor(
                            queryBinding,
                            mode,
                            false,
                            fallbackState,
                            page.hasMore()
                    ),
                    page.hasMore()
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting suggested users for userId {}: {}", currentUserId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to load suggested users",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    /**
     * Stamp last_login_at for the user.
     * Strategy: cache-first, DB updated in hourly batch.
     *  1. Update lastLoginAt on the in-memory User object → instant
     *  2. Re-cache the updated profile in Redis → async internally
     *  3. Mark userId in the Redis dirty-login set → async, O(1)
     *     The hourly LastLoginFlushScheduler drains this set and batch-updates the DB.
     */
    @Transactional
    private void stampLastLogin(User user) {
        if (user == null || user.getId() == null) return;
        try {
            userRepository.updateLastLoginAt(user.getId());
            user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
            safeCacheUser(user);
        } catch (Exception e) {
            logger.warn("Failed to stamp last_login_at for userId={}: {}", user.getId(), e.getClass().getSimpleName());
        }
    }

    private void safeCacheUser(User user) {
        if (user == null || user.getId() == null) return;
        try {
            cacheService.cacheUserProfile(user);
        } catch (Exception e) {
            logger.warn("Failed to cache user profile for userId={}: {}", user.getId(), e.getClass().getSimpleName());
        }
    }

    @Override
    public User updateUserDetails(String userId, UpdateUserRequest updateUserRequest) throws ChallengeSphereException {
        logger.info("Updating self-service profile fields for userId: {}", userId);

        try {
            // Optional<User> cachedUserOpt = cacheService.getCachedUserProfile(userId, User.class);
            User user;
            // if (cachedUserOpt.isPresent()) {
            //     user = cachedUserOpt.get();
            //     logger.debug("Cache hit for userId: {} in updateUserDetails", userId);
            // } else {
            user = userRepository.findById(userId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "User not found with id: " + userId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));
            logger.debug("Loaded userId: {} from DB", userId);
            // }

            if (updateUserRequest.getUsername() != null && !updateUserRequest.getUsername().trim().isEmpty()) {
                String requestedUsername = updateUserRequest.getUsername().trim();
                if (!UsernamePolicy.hasValidLength(requestedUsername)) {
                    throw new ChallengeSphereException(
                            UsernamePolicy.LENGTH_MESSAGE,
                            HttpStatus.BAD_REQUEST,
                            logger
                    );
                }
                if (UsernamePolicy.containsReservedKeyword(requestedUsername)) {
                    throw new ChallengeSphereException(
                            UsernamePolicy.RESERVED_KEYWORD_MESSAGE,
                            HttpStatus.BAD_REQUEST,
                            logger
                    );
                }
                TextContentSafetyPolicy.requireAllowed(requestedUsername, "username");
                if (!requestedUsername.equalsIgnoreCase(user.getUsername())
                        && userRepository.existsByUsernameIgnoreCaseAndIdNot(requestedUsername, userId)) {
                    throw new ChallengeSphereException(
                            "Username is already taken",
                            HttpStatus.CONFLICT,
                            logger
                    );
                }
                user.setUsername(requestedUsername);
                logger.debug("Updated username for user {}", userId);
            }
            String requestedName = firstPresent(
                    updateUserRequest.getName(),
                    updateUserRequest.getFullName(),
                    updateUserRequest.getDisplayName()
            );
            if (requestedName != null) {
                TextContentSafetyPolicy.requireAllowed(requestedName, "display name");
                user.setName(requestedName);
                logger.debug("Updated name for user {}", userId);
            }
            if (updateUserRequest.getProfilePicUrl() != null) {
                String requestedProfilePicUrl = updateUserRequest.getProfilePicUrl().trim();
                if (!requestedProfilePicUrl.equals(user.getProfilePicUrl())) {
                    user.setProfilePicUrl(profilePhotoService.validateUploadedObjectKey(
                            userId,
                            requestedProfilePicUrl
                    ));
                }
                logger.debug("Updated profilePicUrl for user {}", userId);
            }
            if (updateUserRequest.getNotifications() != null) {
                user.setNotifications(updateUserRequest.getNotifications());
                logger.debug("Updated notifications for user {}", userId);
            }
            if (updateUserRequest.getBio() != null) {
                TextContentSafetyPolicy.requireAllowed(updateUserRequest.getBio(), "profile bio");
                user.setBio(updateUserRequest.getBio());
                logger.debug("Updated bio for user {}", userId);
            }
            applyDateOfBirthUpdate(user, updateUserRequest);
            if (updateUserRequest.getGender() != null) {
                user.setGender(updateUserRequest.getGender());
                logger.debug("Updated gender for user {}", userId);
            }
            if (updateUserRequest.getCountry() != null) {
                user.setCountry(updateUserRequest.getCountry().trim());
                logger.debug("Updated country for user {}", userId);
            }
            if (updateUserRequest.getInterests() != null) {
                user.setInterests(updateUserRequest.getInterests());
                logger.debug("Updated interests for user {}", userId);
            }

            User updatedUser = userRepository.save(user);
            safeCacheUser(updatedUser);
            logger.info("Successfully updated user details for userId: {}", userId);
            return withReadableProfilePicUrl(updatedUser);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            logger.warn("Username update rejected by database constraint for userId={}", userId);
            throw new ChallengeSphereException(
                    "Username is already taken",
                    HttpStatus.CONFLICT,
                    logger,
                    e
            );
        } catch (Exception e) {
            logger.error("Error updating user details for userId: {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to update user details",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    private void applyDateOfBirthUpdate(User user, UpdateUserRequest request) throws ChallengeSphereException {
        LocalDate requestedDateOfBirth = request.getDateOfBirth();
        Integer requestedAge = request.getAge();
        if (requestedDateOfBirth == null && requestedAge == null) {
            return;
        }

        LocalDate effectiveDateOfBirth = requestedDateOfBirth != null
                ? requestedDateOfBirth
                : user.getDateOfBirth();
        if (effectiveDateOfBirth == null) {
            throw new ChallengeSphereException(
                    "Date of birth is required when setting age",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        int derivedAge = requireEligibleAccountAge(effectiveDateOfBirth);
        if (requestedAge != null && requestedAge != derivedAge) {
            throw new ChallengeSphereException(
                    "Age must match date of birth",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        user.setDateOfBirth(effectiveDateOfBirth);
        user.setAge(derivedAge);
        logger.debug("Updated dateOfBirth and derived age for user {}", user.getId());
    }

    private int requireEligibleAccountAge(LocalDate dateOfBirth) throws ChallengeSphereException {
        LocalDate today = AccountAgePolicy.currentDate();
        if (!AccountAgePolicy.isEligibleDateOfBirth(dateOfBirth, today)) {
            throw new ChallengeSphereException(
                    "Date of birth must represent an age between 13 and 120",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return AccountAgePolicy.calculateAge(dateOfBirth, today);
    }

    private String normalizeInterestsForQuery(List<String> interests) {
        if (interests == null) {
            return "";
        }
        return interests.stream()
                .map(interest -> interest == null
                        ? ""
                        : interest.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-"))
                .filter(interest -> !interest.isBlank())
                .distinct()
                .sorted()
                .limit(MAX_DISCOVERY_INTERESTS)
                .collect(Collectors.joining(","));
    }

    @Override
    @Transactional
    public FollowActionResponse follow(String followerId, String followingId) throws ChallengeSphereException {
        logger.info("User {} attempting to follow user {}", followerId, followingId);

        try {
            // Validate users cannot follow themselves
            if (followerId.equals(followingId)) {
                throw new ChallengeSphereException(
                        "Users cannot follow themselves",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            User follower;
            User following;
            FollowTransition transition;
            try (var pairLease = acquireUserPairLease(followerId, followingId)) {
                follower = userRepository.findById(followerId)
                        .orElseThrow(() -> new ChallengeSphereException(
                                "Follower not found with id: " + followerId,
                                HttpStatus.NOT_FOUND,
                                logger
                        ));
                following = userRepository.findById(followingId)
                        .orElseThrow(() -> new ChallengeSphereException(
                                "User to follow not found with id: " + followingId,
                                HttpStatus.NOT_FOUND,
                                logger
                        ));

                if (userBlockRepository.existsActiveBlockBetween(followerId, followingId)) {
                    throw new ChallengeSphereException(
                            "Cannot follow this user because a block is active",
                            HttpStatus.FORBIDDEN,
                            logger
                    );
                }

                transition = userRepository.transitionFollow(followerId, followingId, true);
            }
            Follow follow = transition.follow();
            if (!transition.changed()) {
                logger.info("User {} is already following user {}", followerId, followingId);
                return FollowActionResponse.success(followerId, followingId, true);
            }
            invalidateFollowCounterCaches(followerId, followingId, true);

            // Mutate the sorted sets in-place — O(log N), no invalidation needed
            long ts = follow.getCreatedAt() != null ? follow.getCreatedAt().getTime() : System.currentTimeMillis();
            cacheService.addFollow(followerId, followingId, ts);

            // Following set changed — rebuild timeline on next read
            cacheService.invalidateTimeline(followerId);
            cacheService.invalidateAllFeeds();

            try {
                feedTimelineMaterializer.materializeFollowChunk(follow.getId());
            } catch (RuntimeException failure) {
                // The durable pending projection remains queued for the scheduler.
                logger.warn(
                        "Follow timeline backfill remains pending for followId={}: {}",
                        follow.getId(),
                        failure.getClass().getSimpleName()
                );
            }

            notifyFollowAfterCommit(following.getId(), follower.getId());

            logger.info("User {} successfully followed user {}", followerId, followingId);
            return FollowActionResponse.success(followerId, followingId, true);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error following user - followerId: {}, followingId: {}: {}",
                    followerId, followingId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to follow user",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    private void notifyFollowAfterCommit(String recipientUserId, String actorUserId) {
        Runnable notify = () -> {
            try {
                notificationService.notifyFollowAsync(recipientUserId, actorUserId);
            } catch (Exception exception) {
                logger.warn("Failed to schedule follow notification for recipientUserId={}, actorUserId={}: {}",
                        recipientUserId, actorUserId, exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
            return;
        }
        notify.run();
    }

    @Override
    @Transactional
    public FollowActionResponse unfollow(String followerId, String followingId) throws ChallengeSphereException {
        logger.info("User {} attempting to unfollow user {}", followerId, followingId);

        try {
            FollowTransition transition;
            try (var pairLease = acquireUserPairLease(followerId, followingId)) {
                transition = userRepository.transitionFollow(followerId, followingId, false);
            }
            if (!transition.changed()) {
                logger.info("User {} is not following user {}", followerId, followingId);
                return FollowActionResponse.success(followerId, followingId, false);
            }
            invalidateFollowCounterCaches(followerId, followingId, false);

            // Mutate the sorted sets in-place — O(log N), no invalidation needed
            cacheService.removeFollow(followerId, followingId);

            // Following set changed — rebuild timeline on next read
            cacheService.invalidateTimeline(followerId);
            cacheService.invalidateAllFeeds();

            logger.info("User {} successfully unfollowed user {}", followerId, followingId);
            return FollowActionResponse.success(followerId, followingId, false);

        } catch (Exception e) {
            logger.error("Error unfollowing user - followerId: {}, followingId: {}: {}",
                    followerId, followingId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to unfollow user",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FollowRelationshipResponse getFollowRelationship(String viewerUserId, String targetUserId) throws ChallengeSphereException {
        if (viewerUserId == null || viewerUserId.isBlank()
                || targetUserId == null || targetUserId.isBlank()) {
            throw new ChallengeSphereException(
                    "Valid viewer and target user IDs are required",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        if (!userRepository.existsById(targetUserId)) {
            throw new ChallengeSphereException(
                    "User not found with id: " + targetUserId,
                    HttpStatus.NOT_FOUND,
                    logger
            );
        }

        if (!viewerUserId.equals(targetUserId)
                && userBlockRepository.existsActiveBlockBetween(viewerUserId, targetUserId)) {
            throw new ChallengeSphereException(
                    "Not authorized to view this user",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        boolean isFollowing = followRepository.existsByFollowerIdAndFollowingIdAndActive(viewerUserId, targetUserId);
        boolean isFollowedBy = followRepository.existsByFollowerIdAndFollowingIdAndActive(targetUserId, viewerUserId);
        return FollowRelationshipResponse.of(viewerUserId, targetUserId, isFollowing, isFollowedBy);
    }

    @Override
    public FollowersFollowingResponse getFollowers(GetFollowersFollowingRequest request) throws ChallengeSphereException {
        return getConnections(request, true);
    }

    @Override
    public FollowersFollowingResponse getFollowing(GetFollowersFollowingRequest request) throws ChallengeSphereException {
        return getConnections(request, false);
    }

    private FollowersFollowingResponse getConnections(
            GetFollowersFollowingRequest request,
            boolean followers
    ) throws ChallengeSphereException {
        if (request == null || request.getUserId() == null || request.getUserId().isBlank()) {
            throw new ChallengeSphereException("User ID is required", HttpStatus.BAD_REQUEST, logger);
        }
        String userId = request.getUserId();
        String currentUserId = request.getCurrentUserId();
        int boundedLimit = boundedLimit(request.getLimit(), 10, MAX_SOCIAL_LIMIT);
        String type = followers ? "followers" : "following";
        String queryBinding = "users:" + type + ":v2|paging=lek|owner=" + userId
                + "|viewer=" + cursorValue(currentUserId)
                + "|limit=" + boundedLimit;

        try {
            ensureMutualProfileAccess(userId, currentUserId, type);
            DynamoDbCursorCodec.CursorState cursorState = decodeOpaqueCursor(
                    request.getCursor(),
                    queryBinding
            );
            if (!cursorState.shardState().isEmpty()) {
                throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
            }

            Map<String, AttributeValue> continuation = cursorState.lastEvaluatedKey();
            List<User> visibleUsers = new ArrayList<>(boundedLimit);
            int examined = 0;
            boolean hasMore = false;

            while (visibleUsers.size() < boundedLimit && examined < MAX_SOCIAL_CANDIDATES) {
                int queryLimit = Math.min(
                        SOCIAL_QUERY_PAGE_SIZE,
                        MAX_SOCIAL_CANDIDATES - examined
                );
                DynamoPage<Follow> relationshipPage = followers
                        ? followRepository.findActiveFollowersPage(userId, queryLimit, continuation)
                        : followRepository.findActiveFollowingPage(userId, queryLimit, continuation);
                if (relationshipPage.items().isEmpty()) {
                    continuation = relationshipPage.lastEvaluatedKey();
                    hasMore = relationshipPage.hasMore();
                    break;
                }

                List<String> candidateIds = relationshipPage.items().stream()
                        .map(followers ? Follow::getFollowerId : Follow::getFollowingId)
                        .filter(candidateId -> candidateId != null && !candidateId.isBlank())
                        .distinct()
                        .toList();
                Set<String> blockedIds = userBlockRepository.findActiveBlockedCounterpartIds(
                        currentUserId,
                        candidateIds
                );
                Map<String, User> usersById = userRepository.findAllById(candidateIds).stream()
                        .collect(Collectors.toMap(
                                User::getId,
                                user -> user,
                                (first, ignored) -> first
                        ));

                List<Follow> relationships = relationshipPage.items();
                for (int index = 0; index < relationships.size(); index++) {
                    Follow relationship = relationships.get(index);
                    examined++;
                    continuation = followers
                            ? followRepository.activeFollowerContinuationKey(userId, relationship)
                            : followRepository.activeFollowingContinuationKey(userId, relationship);
                    String candidateId = followers
                            ? relationship.getFollowerId()
                            : relationship.getFollowingId();
                    User candidate = usersById.get(candidateId);
                    if (candidate == null || blockedIds.contains(candidateId)) {
                        continue;
                    }
                    visibleUsers.add(candidate);
                    if (visibleUsers.size() == boundedLimit) {
                        hasMore = index + 1 < relationships.size() || relationshipPage.hasMore();
                        break;
                    }
                }

                if (visibleUsers.size() == boundedLimit) {
                    break;
                }
                continuation = relationshipPage.lastEvaluatedKey();
                hasMore = relationshipPage.hasMore();
                if (!hasMore) {
                    break;
                }
            }

            List<User> orderedUsers = withReadableProfilePicUrls(visibleUsers);
            String nextCursor = encodeLastEvaluatedKeyCursor(
                    queryBinding,
                    continuation,
                    hasMore
            );
            logger.info("Found {} {} for userId: {}", orderedUsers.size(), type, userId);
            return followers
                    ? FollowersFollowingResponse.followers(userId, orderedUsers, nextCursor, hasMore)
                    : FollowersFollowingResponse.following(userId, orderedUsers, nextCursor, hasMore);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting {} for userId: {}: {}", type, userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get " + type,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public ToggleUserActivationResponse toggleUserActivation(ToggleActivateUserRequest request) throws ChallengeSphereException {
        String userId = request.getUserId();

        logger.info("Toggling activation status for user with userId: {}", userId);

        try {
            // Find the user
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "User not found with id: " + userId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            // Toggle the active status
            boolean currentStatus = Boolean.TRUE.equals(user.getActive());
            boolean newStatus = !currentStatus;
            userRepository.updateUserActiveStatus(userId, newStatus);
            authTokenStore.revokeAllForUser(userId);
            safeInvalidateUser(userId);

            String action = newStatus ? "activated" : "deactivated";
            logger.info("User {} successfully {} (changed from {} to {})", userId, action, currentStatus, newStatus);

            return ToggleUserActivationResponse.success(userId, newStatus, action);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error toggling activation status for user with userId: {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to toggle user activation status",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public void blockUser(String blockerUserId, String blockedUserId) throws ChallengeSphereException {
        if (blockerUserId == null || blockedUserId == null) {
            throw new ChallengeSphereException("blockerUserId and blockedUserId are required", HttpStatus.BAD_REQUEST, logger);
        }
        if (blockerUserId.equals(blockedUserId)) {
            throw new ChallengeSphereException("Users cannot block themselves", HttpStatus.BAD_REQUEST, logger);
        }
        ensureUserExists(blockerUserId, "Blocker");
        ensureUserExists(blockedUserId, "Blocked user");

        FollowTransition blockerToBlocked;
        FollowTransition blockedToBlocker;
        try (var pairLease = acquireUserPairLease(blockerUserId, blockedUserId)) {
            UserBlock block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
                    .orElseGet(UserBlock::new);
            block.setBlockerUserId(blockerUserId);
            block.setBlockedUserId(blockedUserId);
            block.setActive(true);
            block.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            userBlockRepository.save(block);

            blockerToBlocked = userRepository.transitionFollow(
                    blockerUserId,
                    blockedUserId,
                    false
            );
            blockedToBlocker = userRepository.transitionFollow(
                    blockedUserId,
                    blockerUserId,
                    false
            );
        }
        if (blockerToBlocked.changed()) {
            invalidateFollowCounterCaches(blockerUserId, blockedUserId, false);
            cacheService.removeFollow(blockerUserId, blockedUserId);
            cacheService.invalidateTimeline(blockerUserId);
        }
        if (blockedToBlocker.changed()) {
            invalidateFollowCounterCaches(blockedUserId, blockerUserId, false);
            cacheService.removeFollow(blockedUserId, blockerUserId);
            cacheService.invalidateTimeline(blockedUserId);
        }
        int deletedNotifications = notificationRepository.deleteInteractionsBetweenUsers(
                blockerUserId,
                blockedUserId
        );
        invalidateNotificationUnreadCountsAfterCommit(blockerUserId, blockedUserId);
        logger.info("Removed {} prior notifications between newly blocked users", deletedNotifications);
        cacheService.invalidateAllFeeds();
    }

    @Override
    @Transactional
    public void unblockUser(String blockerUserId, String blockedUserId) throws ChallengeSphereException {
        if (blockerUserId == null || blockedUserId == null) {
            throw new ChallengeSphereException("blockerUserId and blockedUserId are required", HttpStatus.BAD_REQUEST, logger);
        }
        try (var pairLease = acquireUserPairLease(blockerUserId, blockedUserId)) {
            UserBlock block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
                    .orElseThrow(() -> new ChallengeSphereException("Block relationship not found", HttpStatus.NOT_FOUND, logger));
            block.setActive(false);
            block.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            userBlockRepository.save(block);
        }
        cacheService.invalidateAllFeeds();
    }

    private void invalidateNotificationUnreadCountsAfterCommit(String firstUserId, String secondUserId) {
        Runnable invalidate = () -> {
            cacheService.delete("notifications:unread:" + firstUserId);
            cacheService.delete("notifications:unread:" + secondUserId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
            return;
        }
        invalidate.run();
    }

    @Override
    public CursorPageResponse<PublicUserResponse> getBlockedUsers(
            String blockerUserId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        ensureUserExists(blockerUserId, "User");
        int boundedLimit = boundedLimit(limit, 20, MAX_SOCIAL_LIMIT);
        String queryBinding = "users:blocked:v2|order=user-id|paging=lek|owner=" + blockerUserId
                + "|limit=" + boundedLimit;
        DynamoDbCursorCodec.CursorState cursorState = decodeOpaqueCursor(cursor, queryBinding);
        if (!cursorState.shardState().isEmpty()) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
        DynamoPage<UserBlock> page = userBlockRepository.findActiveByBlockerUserIdPage(
                blockerUserId,
                boundedLimit,
                cursorState.lastEvaluatedKey()
        );
        List<String> blockedIds = page.items().stream()
                .map(UserBlock::getBlockedUserId)
                .toList();
        List<User> users = blockedIds.isEmpty()
                ? List.of()
                : userRepository.findAllById(blockedIds);
        List<PublicUserResponse> responses = orderUsersById(users, blockedIds).stream()
                .map(this::restrictedProfile)
                .map(this::withReadableProfilePicUrl)
                .map(PublicUserResponse::from)
                .toList();
        return CursorPageResponse.of(
                responses,
                encodeLastEvaluatedKeyCursor(
                        queryBinding,
                        page.lastEvaluatedKey(),
                        page.hasMore()
                ),
                page.hasMore()
        );
    }

    @Override
    @Transactional
    public void reportUser(String reporterUserId, String reportedUserId, String reason, String details) throws ChallengeSphereException {
        if (reporterUserId == null || reportedUserId == null) {
            throw new ChallengeSphereException("reporterUserId and reportedUserId are required", HttpStatus.BAD_REQUEST, logger);
        }
        if (reporterUserId.equals(reportedUserId)) {
            throw new ChallengeSphereException("Users cannot report themselves", HttpStatus.BAD_REQUEST, logger);
        }
        ensureUserExists(reporterUserId, "Reporter");
        ensureUserExists(reportedUserId, "Reported user");

        String normalizedReason = reason == null || reason.isBlank() ? "OTHER" : reason.trim();
        UserReport report = new UserReport();
        report.setReporterUserId(reporterUserId);
        report.setReportedUserId(reportedUserId);
        report.setReason(normalizedReason.length() > 128 ? normalizedReason.substring(0, 128) : normalizedReason);
        report.setDetails(details == null || details.isBlank() ? null : details.trim());
        UserReport savedReport = userReportRepository.save(report);
        String alertReason = savedReport.getReason() == null
                ? ""
                : savedReport.getReason().trim().toUpperCase(Locale.ROOT);
        if (List.of("CHILD_SAFETY", "SELF_HARM", "VIOLENCE").contains(alertReason)) {
            logger.error(
                    "URGENT_MODERATION_REPORT id={} reason={} targetType=ACCOUNT targetId={}",
                    savedReport.getId(),
                    alertReason,
                    reportedUserId
            );
        }
    }

    @Override
    public boolean isBlockedEitherDirection(String firstUserId, String secondUserId) {
        if (firstUserId == null || secondUserId == null || firstUserId.equals(secondUserId)) {
            return false;
        }
        return userBlockRepository.existsActiveBlockBetween(firstUserId, secondUserId);
    }

    @Override
    @Transactional
    public UserRegistrationResponse registerUser(UserRegistrationRequest request) throws ChallengeSphereException {
        logger.info("Registering new user");

        try {
            if (request.getDateOfBirth() == null) {
                throw new ChallengeSphereException(
                        "Date of birth is required",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            int derivedAge = requireEligibleAccountAge(request.getDateOfBirth());

            // Validate if user already exists
            String requestedUsername = request.getUsername() == null ? null : request.getUsername().trim();
            if (requestedUsername != null) {
                if (UsernamePolicy.containsReservedKeyword(requestedUsername)) {
                    throw new ChallengeSphereException(
                            UsernamePolicy.RESERVED_KEYWORD_MESSAGE,
                            HttpStatus.BAD_REQUEST,
                            logger
                    );
                }
                if (!UsernamePolicy.hasValidLength(requestedUsername)) {
                    throw new ChallengeSphereException(
                            UsernamePolicy.LENGTH_MESSAGE,
                            HttpStatus.BAD_REQUEST,
                            logger
                    );
                }
                Optional<User> existingUser = userRepository.findByUsername(requestedUsername);
                if (existingUser.isPresent()) {
                    logger.warn("Registration rejected because username already exists");
                    throw new ChallengeSphereException(
                            "Username already exists",
                            HttpStatus.CONFLICT,
                            logger
                    );
                }
            }

            if (request.getEmailId() != null) {
                Optional<User> existingUser = userRepository.findByEmailId(request.getEmailId());
                if (existingUser.isPresent()) {
                    logger.warn("Registration rejected because email already exists");
                    throw new ChallengeSphereException(
                            "Email already exists",
                            HttpStatus.CONFLICT,
                            logger
                    );
                }
            }

            if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
                Optional<User> existingUser = userRepository.findByPhone(request.getPhone());
                if (existingUser.isPresent()) {
                    logger.warn("Registration rejected because phone already exists");
                    throw new ChallengeSphereException(
                            "Phone number already exists",
                            HttpStatus.CONFLICT,
                            logger
                    );
                }
            }

            // Create new user
            User newUser = new User();
            TextContentSafetyPolicy.requireAllowed(requestedUsername, "username");
            TextContentSafetyPolicy.requireAllowed(request.getBio(), "profile bio");
            newUser.setUsername(requestedUsername);
            newUser.setEmailId(request.getEmailId());
            newUser.setPhone(request.getPhone());
            newUser.setBio(request.getBio());
            newUser.setDateOfBirth(request.getDateOfBirth());
            newUser.setAge(derivedAge);

            validatePasswordPolicy(request.getPassword());
            newUser.setHashedPassword(encodePassword(request.getPassword()));

            // Set defaults
            newUser.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            newUser.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
            newUser.setGenuinenessScore(100);
            newUser.setActive(true);
            newUser.setChallengesCreated(0);
            newUser.setChallengesAccepted(0);
            newUser.setFollowers(0);
            newUser.setFollowing(0);
            newUser.setPremiumUser(0);
            newUser.setNotifications(1);

            // Save user
            User savedUser = userRepository.save(newUser);
            safeCacheUser(savedUser);
            logger.info("Successfully registered user with id={}", savedUser.getId());

            return UserRegistrationResponse.success(savedUser);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("User registration failed | exception={}", e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to register user",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    private ConditionalKeyLease.Lease acquireUserPairLease(String firstUserId, String secondUserId) {
        if (firstUserId == null || firstUserId.isBlank()
                || secondUserId == null || secondUserId.isBlank()) {
            throw new IllegalArgumentException("Both user IDs are required for a relationship transition");
        }
        String lowerId = firstUserId.compareTo(secondUserId) <= 0 ? firstUserId : secondUserId;
        String upperId = lowerId.equals(firstUserId) ? secondUserId : firstUserId;
        String pairKey = lowerId.length() + ":" + lowerId + "|" + upperId.length() + ":" + upperId;
        return conditionalKeyLease.acquireLease(USER_PAIR_LEASE_SCOPE, pairKey);
    }

    // The edge, pair projection, and both counters have already committed in
    // one DynamoDB transaction. Cache invalidation is intentionally best effort.
    private void invalidateFollowCounterCaches(
            String followerId,
            String followingId,
            boolean active
    ) {
        safeInvalidateUser(followerId);
        safeInvalidateUser(followingId);
        logger.debug("Committed atomic follow transition for followerId: {}, followingId: {}, active: {}",
                followerId, followingId, active);
    }

    private void safeInvalidateUser(String userId) {
        if (userId == null) return;
        try {
            cacheService.invalidateUserProfile(userId);
        } catch (Exception e) {
            logger.warn("Failed to invalidate cached user profile for userId={}: {}", userId, e.getClass().getSimpleName());
        }
    }

    private User restrictProfileForViewer(User user, String currentUserId) throws ChallengeSphereException {
        if (user == null || user.getId() == null) {
            return withReadableProfilePicUrl(attachSummaryCounts(user));
        }
        if (currentUserId != null && !currentUserId.equals(user.getId())
                && userBlockRepository.existsActiveBlockBetween(currentUserId, user.getId())) {
            throw new ChallengeSphereException("Not authorized to view this user", HttpStatus.FORBIDDEN, logger);
        }
        if (currentUserId == null) {
            return withReadableProfilePicUrl(restrictedProfile(user));
        }
        if (followRepository.existsActiveMutualFollow(currentUserId, user.getId())) {
            return withReadableProfilePicUrl(attachSummaryCounts(user));
        }

        return withReadableProfilePicUrl(restrictedProfile(user));
    }

    private User restrictedProfile(User user) {
        attachSummaryCounts(user);
        User restricted = new User();
        restricted.setId(user.getId());
        restricted.setUsername(user.getUsername());
        restricted.setProfilePicUrl(user.getProfilePicUrl());
        restricted.setActive(user.getActive());
        restricted.setCreatedAt(user.getCreatedAt());
        restricted.setFollowers(user.getFollowers());
        restricted.setFollowing(user.getFollowing());
        restricted.setChallengesCreated(user.getChallengesCreated());
        restricted.setChallengesAccepted(user.getChallengesAccepted());
        restricted.setGenuinenessScore(user.getGenuinenessScore());
        restricted.setPremiumUser(0);
        restricted.setNotifications(0);
        restricted.setSubmissionsCount(user.getSubmissionsCount());
        return restricted;
    }

    private User attachSummaryCounts(User user) {
        if (user == null || user.getId() == null) {
            return user;
        }
        try {
            user.setSubmissionsCount(submissionRepository.countActiveByUserId(user.getId()));
        } catch (Exception e) {
            logger.warn("Failed to attach submissions count for userId={}: {}", user.getId(), e.getClass().getSimpleName());
            user.setSubmissionsCount(0);
        }
        return user;
    }

    private User withReadableProfilePicUrl(User user) {
        if (user == null) {
            return user;
        }
        User readableUser = new User();
        BeanUtils.copyProperties(user, readableUser);
        if (user.getProfilePicUrl() == null || user.getProfilePicUrl().isBlank() || mediaUrlService == null) {
            return readableUser;
        }
        try {
            readableUser.setProfilePicUrl(
                    mediaUrlService.toReadableProfilePhoto(user.getId(), user.getProfilePicUrl()));
        } catch (Exception e) {
            logger.warn("Failed to prepare readable profile photo URL for userId={}: {}", user.getId(), e.getClass().getSimpleName());
            readableUser.setProfilePicUrl(null);
        }
        return readableUser;
    }

    private List<User> withReadableProfilePicUrls(List<User> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.stream()
                .map(this::withReadableProfilePicUrl)
                .toList();
    }

    private List<User> orderUsersById(List<User> users, List<String> orderedIds) {
        if (users == null || users.isEmpty() || orderedIds == null || orderedIds.isEmpty()) {
            return List.of();
        }
        Map<String, User> usersById = users.stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        return orderedIds.stream()
                .map(usersById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private int boundedLimit(Integer requested, int defaultValue, int maximum) {
        int resolved = requested == null ? defaultValue : requested;
        return Math.max(1, Math.min(resolved, maximum));
    }

    private DynamoDbCursorCodec.CursorState decodeOpaqueCursor(
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

    private String encodeShardCursor(
            String queryBinding,
            Map<String, Map<String, AttributeValue>> shardState,
            boolean hasMore
    ) {
        if (!hasMore) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(Map.of(), shardState)
        );
    }

    private String encodeLastEvaluatedKeyCursor(
            String queryBinding,
            Map<String, AttributeValue> lastEvaluatedKey,
            boolean hasMore
    ) {
        if (!hasMore) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(lastEvaluatedKey, Map.of())
        );
    }

    private String suggestionMode(
            DynamoDbCursorCodec.CursorState cursorState,
            boolean interestsEmpty
    ) throws ChallengeSphereException {
        AttributeValue modeValue = cursorState.lastEvaluatedKey().get(CURSOR_MODE);
        if (modeValue == null) {
            if (!cursorState.lastEvaluatedKey().isEmpty()
                    || !cursorState.shardState().isEmpty()) {
                throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
            }
            return interestsEmpty ? SUGGESTION_MODE_FALLBACK : SUGGESTION_MODE_INTERESTS;
        }
        String mode = modeValue.s();
        if (!SUGGESTION_MODE_INTERESTS.equals(mode)
                && !SUGGESTION_MODE_FALLBACK.equals(mode)) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
        if (interestsEmpty && SUGGESTION_MODE_INTERESTS.equals(mode)) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
        return mode;
    }

    private boolean cursorFlag(
            DynamoDbCursorCodec.CursorState cursorState,
            String name
    ) throws ChallengeSphereException {
        AttributeValue value = cursorState.lastEvaluatedKey().get(name);
        if (value == null) {
            return false;
        }
        if ("1".equals(value.s())) {
            return true;
        }
        if ("0".equals(value.s())) {
            return false;
        }
        throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
    }

    private String encodeSuggestionCursor(
            String queryBinding,
            String mode,
            boolean seenInterestSuggestion,
            Map<String, Map<String, AttributeValue>> shardState,
            boolean hasMore
    ) {
        if (!hasMore) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(
                        Map.of(
                                CURSOR_MODE,
                                AttributeValue.fromS(mode),
                                CURSOR_SEEN,
                                AttributeValue.fromS(seenInterestSuggestion ? "1" : "0")
                        ),
                        shardState
                )
        );
    }

    private UserSearchResponse suggestedUsersResponse(
            List<User> users,
            String nextCursor,
            boolean hasMore
    ) {
        List<User> visibleUsers = users.stream()
                .map(this::restrictedProfile)
                .map(this::withReadableProfilePicUrl)
                .toList();
        return UserSearchResponse.of("suggested", visibleUsers, nextCursor, hasMore);
    }

    private String cursorValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalizeUserSearchQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private void ensureMutualProfileAccess(String ownerUserId, String viewerUserId, String contentType)
            throws ChallengeSphereException {
        if (ownerUserId != null && viewerUserId != null && userBlockRepository.existsActiveBlockBetween(ownerUserId, viewerUserId)) {
            throw new ChallengeSphereException(
                    "Not authorized to view this user's " + contentType,
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (ownerUserId == null || viewerUserId == null || followRepository.existsActiveMutualFollow(viewerUserId, ownerUserId)) {
            return;
        }
        throw new ChallengeSphereException(
                "You can only view this user's " + contentType + " if you follow each other",
                HttpStatus.FORBIDDEN,
                logger
        );
    }

    private String encodePassword(String password) {
        if (password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$")) {
            return password;
        }
        return passwordEncoder.encode(password);
    }

    private void validatePasswordPolicy(String password) throws ChallengeSphereException {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new ChallengeSphereException("Password must be between 8 and 128 characters",
                    HttpStatus.BAD_REQUEST, logger);
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new ChallengeSphereException("Password must contain at least one letter and one number",
                    HttpStatus.BAD_REQUEST, logger);
        }
    }

    private void ensureUserExists(String userId, String label) throws ChallengeSphereException {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ChallengeSphereException(label + " not found with id: " + userId, HttpStatus.NOT_FOUND, logger);
        }
    }

    private String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    @Override
    public User getUserDetails(String userId, String emailId, String username) throws ChallengeSphereException {
        Optional<User> optionalUser =
                redisCacheServiceImpl.getCachedUserProfile(
                        userId,
                        emailId,
                        username,
                        User.class
                );

        if (optionalUser.isPresent()) {
            return optionalUser.get();
        }

        if (userId != null) {
            return userRepository.findById(userId)
                    .orElseThrow(() ->
                            new ChallengeSphereException(
                                    "User not found",
                                    HttpStatus.UNAUTHORIZED,
                                    logger));
        }

        if (emailId != null && !emailId.isBlank()) {
            return userRepository.findByEmailId(emailId).orElseThrow(() ->
                            new ChallengeSphereException(
                                    "User not found",
                                    HttpStatus.UNAUTHORIZED,
                                    logger));
        }

        if (username != null && !username.isBlank()) {
            return userRepository.findByUsername(username)
                    .orElseThrow(() ->
                            new ChallengeSphereException(
                                    "User not found",
                                    HttpStatus.UNAUTHORIZED,
                                    logger));
        }

        throw new IllegalArgumentException(
                "At least one of userId, emailId, or username must be provided");
    }
}
