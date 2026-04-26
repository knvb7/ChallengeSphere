package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.UserRepository;
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

    @Override
    public User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException {
        Long userId = getUserPayload.getUserId();
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
                        return cached.get();
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
                    return found;
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
                    return found;
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
                    return found;
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
                    return found;
                }
            }

            logger.warn("User not found with any of the provided parameters - userId: {}, emailId: {}, phone: {}, username: {}",
                    userId, emailId, phone, username);
        } catch (Exception e) {
            logger.error("Error retrieving user with parameters - userId: {}, emailId: {}, phone: {}, username: {}. Error: {}",
                    userId, emailId, phone, username, e.getMessage(), e);
        }

        throw new ChallengeSphereException("User not found with provided parameters", HttpStatus.NOT_FOUND, logger);
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
                user.setHashedPassword(updateUserRequest.getHashedPassword());
                logger.debug("Updated hashedPassword for user {}", userId);
            }
            if (updateUserRequest.getGenuinenessScore() != null) {
                user.setGenuinenessScore(updateUserRequest.getGenuinenessScore());
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
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting followers for userId: {}, page: {}, pageSize: {}", userId, page, pageSize);

        try {
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
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting following for userId: {}, page: {}, pageSize: {}", userId, page, pageSize);

        try {
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

            // Hash password with BCrypt
            newUser.setHashedPassword(request.getPassword());

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
}
