package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.api.response.UserSearchResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserBlock;
import com.ironside.challengesphere.model.UserReport;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserReportRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.UserService;

@Service
public class UserServiceImpl implements UserService {
    private static final Logger logger = LogManager.getLogger(UserServiceImpl.class);

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
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserReportRepository userReportRepository;

    @Override
    public User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException {
        Long userId = getUserPayload.getUserId();
        Long currentUserId = getUserPayload.getCurrentUserId();
        String emailId = getUserPayload.getEmailId();
        String phone = getUserPayload.getPhone();
        String username = getUserPayload.getUsername();
        logger.info("Retrieving user with parameters - userId: {}, emailId: {}, phone: {}, username: {}",
                userId, emailId, phone, username);

        try {
            // 1. Fetch by userId — cache first, DB on miss or cache failure
            if (userId != null) {
                try {
                    Optional<User> cached = cacheService.getCachedUserProfile(userId, User.class);
                    if (cached.isPresent()) {
                        logger.info("Cache hit for userId: {}", userId);
                        stampLastLogin(cached.get());
                    return restrictProfileForViewer(cached.get(), currentUserId);
                    }
                } catch (Exception cacheEx) {
                    logger.warn("Cache lookup failed for userId={}, falling back to DB: {}", userId, cacheEx.getMessage());
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
                    logger.info("User found by emailId: {}", emailId);
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
                    logger.info("User found by phone: {}", phone);
                    final User found = user.get();
                    stampLastLogin(found);
                    safeCacheUser(found);
                    return restrictProfileForViewer(found, currentUserId);
                }
            }

            // 4. Fetch by username from DB
            if (username != null && !username.trim().isEmpty()) {
                String usernameKey = username.trim();
                logger.debug("Fetching from DB for username: {}", username);
                Optional<User> user = userRepository.findByUsername(usernameKey);
                if (user.isPresent()) {
                    logger.info("User found by username: {}", username);
                    final User found = user.get();
                    stampLastLogin(found);
                    safeCacheUser(found);
                    return restrictProfileForViewer(found, currentUserId);
                }
            }

            logger.warn("User not found with any of the provided parameters - userId: {}, emailId: {}, phone: {}, username: {}",
                    userId, emailId, phone, username);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error retrieving user with parameters - userId: {}, emailId: {}, phone: {}, username: {}. Error: {}",
                    userId, emailId, phone, username, e.getMessage(), e);
        }

        throw new ChallengeSphereException("User not found with provided parameters", HttpStatus.NOT_FOUND, logger);
    }

    @Override
    public UserSearchResponse searchUsers(String query, Long currentUserId, Integer page, Integer pageSize)
            throws ChallengeSphereException {
        int safePage = Math.max(1, page == null ? 1 : page);
        int safePageSize = Math.min(50, Math.max(1, pageSize == null ? 10 : pageSize));
        String normalizedQuery = normalizeUserSearchQuery(query);

        if (normalizedQuery.isEmpty()) {
            return UserSearchResponse.empty(normalizedQuery, safePage, safePageSize);
        }

        try {
            Long viewerId = currentUserId == null ? -1L : currentUserId;
            Page<User> results = userRepository.searchDiscoverUsers(
                    normalizedQuery,
                    viewerId,
                    PageRequest.of(safePage - 1, safePageSize)
            );

            List<User> visibleUsers = new java.util.ArrayList<>();
            for (User user : results.getContent()) {
                visibleUsers.add(restrictedProfile(user));
            }

            return UserSearchResponse.of(
                    normalizedQuery,
                    visibleUsers,
                    safePage,
                    safePageSize,
                    results.getTotalElements()
            );
        } catch (Exception e) {
            logger.error("Error searching users for query '{}': {}", normalizedQuery, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to search users",
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
            logger.warn("Failed to stamp last_login_at for userId={}: {}", user.getId(), e.getMessage());
        }
    }

    private void safeCacheUser(User user) {
        if (user == null || user.getId() == null) return;
        try {
            cacheService.cacheUserProfile(user.getId(), user);
        } catch (Exception e) {
            logger.warn("Failed to cache user profile for userId={}: {}", user.getId(), e.getMessage());
        }
    }

    @Override
    public User updateUserDetails(UpdateUserRequest updateUserRequest) throws ChallengeSphereException {
        Long userId = updateUserRequest.getUserId();
        logger.info("Updating user details for userId: {} with emailId: {}, phone: {}, username: {}",
                   userId, updateUserRequest.getEmailId(), updateUserRequest.getPhone(), updateUserRequest.getUsername());

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

            if (updateUserRequest.getEmailId() != null && !updateUserRequest.getEmailId().trim().isEmpty()) {
                user.setEmailId(updateUserRequest.getEmailId().trim());
                logger.debug("Updated emailId for user {}", userId);
            }
            if (updateUserRequest.getPhone() != null && !updateUserRequest.getPhone().trim().isEmpty()) {
                user.setPhone(updateUserRequest.getPhone().trim());
                logger.debug("Updated phone for user {}", userId);
            }
            if (updateUserRequest.getUsername() != null && !updateUserRequest.getUsername().trim().isEmpty()) {
                user.setUsername(updateUserRequest.getUsername().trim());
                logger.debug("Updated username for user {}", userId);
            }
            if (updateUserRequest.getHashedPassword() != null && !updateUserRequest.getHashedPassword().trim().isEmpty()) {
                user.setHashedPassword(encodePassword(updateUserRequest.getHashedPassword()));
                logger.debug("Updated hashedPassword for user {}", userId);
            }
            Integer incomingGenuinityScore = updateUserRequest.getGenuinityScore() != null
                    ? updateUserRequest.getGenuinityScore()
                    : updateUserRequest.getGenuinenessScore();
            if (incomingGenuinityScore != null) {
                user.setGenuinenessScore(incomingGenuinityScore);
                logger.debug("Updated genuinenessScore for user {}", userId);
            }
            if (updateUserRequest.getActive() != null) {
                user.setActive(updateUserRequest.getActive());
                logger.debug("Updated active for user {}", userId);
            }
            if (updateUserRequest.getChallengesCreated() != null) {
                user.setChallengesCreated(updateUserRequest.getChallengesCreated());
                logger.debug("Updated challengesCreated for user {}", userId);
            }
            if (updateUserRequest.getChallengesAccepted() != null) {
                user.setChallengesAccepted(updateUserRequest.getChallengesAccepted());
                logger.debug("Updated challengesAccepted for user {}", userId);
            }
            if (updateUserRequest.getProfilePicUrl() != null) {
                user.setProfilePicUrl(updateUserRequest.getProfilePicUrl());
                logger.debug("Updated profilePicUrl for user {}", userId);
            }
            if (updateUserRequest.getPremiumUser() != null) {
                user.setPremiumUser(updateUserRequest.getPremiumUser());
                logger.debug("Updated premiumUser for user {}", userId);
            }
            if (updateUserRequest.getNotifications() != null) {
                user.setNotifications(updateUserRequest.getNotifications());
                logger.debug("Updated notifications for user {}", userId);
            }
            if (updateUserRequest.getBio() != null) {
                user.setBio(updateUserRequest.getBio());
                logger.debug("Updated bio for user {}", userId);
            }

            User updatedUser = userRepository.save(user);
            safeCacheUser(updatedUser);
            logger.info("Successfully updated user details for userId: {}", userId);
            return updatedUser;

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error updating user details for userId: {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to update user details",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public FollowActionResponse follow(Long followerId, Long followingId) throws ChallengeSphereException {
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

            User follower = userRepository.findById(followerId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Follower not found with id: " + followerId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));
            User following = userRepository.findById(followingId)
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

            // Check if already following
            Optional<Follow> existingFollow = followRepository.findByFollowerIdAndFollowingId(followerId, followingId);
            if (existingFollow.isPresent() && existingFollow.get().getActive()) {
                logger.info("User {} is already following user {}", followerId, followingId);
                return FollowActionResponse.success(followerId, followingId, true);
            }

            // Create or reactivate follow
            Follow follow;
            if (existingFollow.isPresent()) {
                follow = existingFollow.get();
                follow.setActive(true);
            } else {
                follow = new Follow();
                follow.setFollowerId(followerId);
                follow.setFollowingId(followingId);
                follow.setActive(true);
                follow.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            }

            followRepository.save(follow);
            updateFollowCounts(followerId, followingId, true);

            // Mutate the sorted sets in-place — O(log N), no invalidation needed
            // long ts = follow.getCreatedAt() != null ? follow.getCreatedAt().getTime() : System.currentTimeMillis();
            // cacheService.addFollow(followerId, followingId, ts);

            // Following set changed — rebuild timeline on next read
            // cacheService.invalidateTimeline(followerId);
            cacheService.invalidateAllFeeds();

            try {
                notificationService.notifyFollow(following.getId(), follower.getId());
            } catch (Exception e) {
                logger.warn("Failed to create follow notification for followerId={}, followingId={}: {}",
                        followerId, followingId, e.getMessage());
            }

            logger.info("User {} successfully followed user {}", followerId, followingId);
            return FollowActionResponse.success(followerId, followingId, true);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error following user - followerId: {}, followingId: {}: {}",
                    followerId, followingId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to follow user",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public FollowActionResponse unfollow(Long followerId, Long followingId) throws ChallengeSphereException {
        logger.info("User {} attempting to unfollow user {}", followerId, followingId);

        try {
            // Check if follow exists
            Optional<Follow> existingFollow = followRepository.findByFollowerIdAndFollowingId(followerId, followingId);
            if (existingFollow.isEmpty() || !existingFollow.get().getActive()) {
                logger.info("User {} is not following user {}", followerId, followingId);
                return FollowActionResponse.success(followerId, followingId, false);
            }

            // Soft delete by setting active to false
            Follow follow = existingFollow.get();
            follow.setActive(false);
            followRepository.save(follow);

            updateFollowCounts(followerId, followingId, false);

            // Mutate the sorted sets in-place — O(log N), no invalidation needed
            // cacheService.removeFollow(followerId, followingId);

            // Following set changed — rebuild timeline on next read
            // cacheService.invalidateTimeline(followerId);
            cacheService.invalidateAllFeeds();

            logger.info("User {} successfully unfollowed user {}", followerId, followingId);
            return FollowActionResponse.success(followerId, followingId, false);

        } catch (Exception e) {
            logger.error("Error unfollowing user - followerId: {}, followingId: {}: {}",
                    followerId, followingId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to unfollow user",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public FollowersFollowingResponse getFollowers(GetFollowersFollowingRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        Long currentUserId = request.getCurrentUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting followers for userId: {}, page: {}, pageSize: {}", userId, page, pageSize);

        try {
            ensureMutualProfileAccess(userId, currentUserId, "followers");

            long offset = (long) (page - 1) * pageSize;

            // DB-only path
            List<Follow> allFollows = followRepository.findActiveFollowsByFollowingId(userId);
            int totalCount = allFollows.size();
            List<Long> pageIds = allFollows.stream().skip(offset).limit(pageSize)
                    .map(Follow::getFollowerId).collect(Collectors.toList());
            List<User> followers = pageIds.isEmpty() ? List.of() : userRepository.findAllById(pageIds);

            logger.info("Found {} followers for userId: {} (page {})",
                    followers.size(), userId, page);

            return FollowersFollowingResponse.followers(userId, followers, page, pageSize, totalCount);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting followers for userId: {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get followers",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public FollowersFollowingResponse getFollowing(GetFollowersFollowingRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        Long currentUserId = request.getCurrentUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting following for userId: {}, page: {}, pageSize: {}", userId, page, pageSize);

        try {
            ensureMutualProfileAccess(userId, currentUserId, "following");

            long offset = (long) (page - 1) * pageSize;

            // DB-only path
            List<Follow> allFollows = followRepository.findActiveFollowsByFollowerId(userId);
            int totalCount = allFollows.size();
            List<Long> pageIds = allFollows.stream().skip(offset).limit(pageSize)
                    .map(Follow::getFollowingId).collect(Collectors.toList());
            List<User> following = pageIds.isEmpty() ? List.of() : userRepository.findAllById(pageIds);

            logger.info("Found {} following for userId: {} (page {})",
                    following.size(), userId, page);

            return FollowersFollowingResponse.following(userId, following, page, pageSize, totalCount);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting following for userId: {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get following",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public ToggleUserActivationResponse toggleUserActivation(ToggleActivateUserRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();

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
            safeInvalidateUser(userId);

            String action = newStatus ? "activated" : "deactivated";
            logger.info("User {} successfully {} (changed from {} to {})", userId, action, currentStatus, newStatus);

            return ToggleUserActivationResponse.success(userId, newStatus, action);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error toggling activation status for user with userId: {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to toggle user activation status",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public void blockUser(Long blockerUserId, Long blockedUserId) throws ChallengeSphereException {
        if (blockerUserId == null || blockedUserId == null) {
            throw new ChallengeSphereException("blockerUserId and blockedUserId are required", HttpStatus.BAD_REQUEST, logger);
        }
        if (blockerUserId.equals(blockedUserId)) {
            throw new ChallengeSphereException("Users cannot block themselves", HttpStatus.BAD_REQUEST, logger);
        }
        ensureUserExists(blockerUserId, "Blocker");
        ensureUserExists(blockedUserId, "Blocked user");

        UserBlock block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
                .orElseGet(UserBlock::new);
        block.setBlockerUserId(blockerUserId);
        block.setBlockedUserId(blockedUserId);
        block.setActive(true);
        block.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        userBlockRepository.save(block);

        followRepository.findByFollowerIdAndFollowingId(blockerUserId, blockedUserId).ifPresent(existing -> {
            if (Boolean.TRUE.equals(existing.getActive())) {
                existing.setActive(false);
                followRepository.save(existing);
                updateFollowCounts(blockerUserId, blockedUserId, false);
            }
        });
        followRepository.findByFollowerIdAndFollowingId(blockedUserId, blockerUserId).ifPresent(existing -> {
            if (Boolean.TRUE.equals(existing.getActive())) {
                existing.setActive(false);
                followRepository.save(existing);
                updateFollowCounts(blockedUserId, blockerUserId, false);
            }
        });
        cacheService.invalidateAllFeeds();
    }

    @Override
    @Transactional
    public void unblockUser(Long blockerUserId, Long blockedUserId) throws ChallengeSphereException {
        if (blockerUserId == null || blockedUserId == null) {
            throw new ChallengeSphereException("blockerUserId and blockedUserId are required", HttpStatus.BAD_REQUEST, logger);
        }
        UserBlock block = userBlockRepository.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
                .orElseThrow(() -> new ChallengeSphereException("Block relationship not found", HttpStatus.NOT_FOUND, logger));
        block.setActive(false);
        block.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        userBlockRepository.save(block);
        cacheService.invalidateAllFeeds();
    }

    @Override
    public List<User> getBlockedUsers(Long blockerUserId) throws ChallengeSphereException {
        ensureUserExists(blockerUserId, "User");
        List<Long> blockedIds = userBlockRepository.findActiveByBlockerUserId(blockerUserId).stream()
                .map(UserBlock::getBlockedUserId)
                .toList();
        return blockedIds.isEmpty() ? List.of() : userRepository.findAllById(blockedIds);
    }

    @Override
    @Transactional
    public void reportUser(Long reporterUserId, Long reportedUserId, String reason, String details) throws ChallengeSphereException {
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
        userReportRepository.save(report);
    }

    @Override
    public boolean isBlockedEitherDirection(Long firstUserId, Long secondUserId) {
        if (firstUserId == null || secondUserId == null || firstUserId.equals(secondUserId)) {
            return false;
        }
        return userBlockRepository.existsActiveBlockBetween(firstUserId, secondUserId);
    }

    @Override
    @Transactional
    public UserRegistrationResponse registerUser(UserRegistrationRequest request) throws ChallengeSphereException {
        logger.info("Registering new user with username: {}, email: {}", request.getUsername(), request.getEmailId());

        try {
            // Validate if user already exists
            if (request.getUsername() != null) {
                Optional<User> existingUser = userRepository.findByUsername(request.getUsername());
                if (existingUser.isPresent()) {
                    logger.warn("Registration failed: Username already exists: {}", request.getUsername());
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
                    logger.warn("Registration failed: Email already exists: {}", request.getEmailId());
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
                    logger.warn("Registration failed: Phone already exists: {}", request.getPhone());
                    throw new ChallengeSphereException(
                            "Phone number already exists",
                            HttpStatus.CONFLICT,
                            logger
                    );
                }
            }

            // Create new user
            User newUser = new User();
            newUser.setUsername(request.getUsername());
            newUser.setEmailId(request.getEmailId());
            newUser.setPhone(request.getPhone());
            newUser.setBio(request.getBio());

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
            logger.info("Successfully registered user with id: {}, username: {}", savedUser.getId(), savedUser.getUsername());

            return UserRegistrationResponse.success(savedUser);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error registering user: {}", e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to register user: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    // Runs inside the caller's @Transactional boundary so counter updates and the follows-row
    // write commit together. Any exception rolls back both — counters stay in sync with follows.
    protected void updateFollowCounts(Long followerId, Long followingId, boolean isFollow) {
        int delta = isFollow ? 1 : -1;
        userRepository.updateFollowingCount(followerId, delta);
        userRepository.updateFollowersCount(followingId, delta);
        safeInvalidateUser(followerId);
        safeInvalidateUser(followingId);
        logger.debug("Updated follow counts for followerId: {}, followingId: {}, action: {}",
                followerId, followingId, isFollow ? "follow" : "unfollow");
    }

    private void safeInvalidateUser(Long userId) {
        if (userId == null) return;
        try {
            cacheService.invalidateUserProfile(userId);
        } catch (Exception e) {
            logger.warn("Failed to invalidate cached user profile for userId={}: {}", userId, e.getMessage());
        }
    }

    private User restrictProfileForViewer(User user, Long currentUserId) throws ChallengeSphereException {
        if (user == null || user.getId() == null) {
            return attachSummaryCounts(user);
        }
        if (currentUserId != null && !currentUserId.equals(user.getId())
                && userBlockRepository.existsActiveBlockBetween(currentUserId, user.getId())) {
            throw new ChallengeSphereException("Not authorized to view this user", HttpStatus.FORBIDDEN, logger);
        }
        if (currentUserId == null) {
            return restrictedProfile(user);
        }
        if (followRepository.existsActiveMutualFollow(currentUserId, user.getId())) {
            return attachSummaryCounts(user);
        }

        return restrictedProfile(user);
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
            logger.warn("Failed to attach submissions count for userId={}: {}", user.getId(), e.getMessage());
            user.setSubmissionsCount(0);
        }
        return user;
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

    private void ensureMutualProfileAccess(Long ownerUserId, Long viewerUserId, String contentType)
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

    private void ensureUserExists(Long userId, String label) throws ChallengeSphereException {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ChallengeSphereException(label + " not found with id: " + userId, HttpStatus.NOT_FOUND, logger);
        }
    }
}
