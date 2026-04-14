package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.request.GetChallengeParticipantsRequest;
import com.ironside.challengesphere.api.request.GetChallengesByHashtagRequest;
import com.ironside.challengesphere.api.request.GetPopularChallengesRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeDetailResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengeProgressResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.MyChallengesResponse;
import com.ironside.challengesphere.api.response.PopularChallengesResponse;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.RecentChallengesResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ChallengeService;

@Service
public class ChallengeServiceImpl implements ChallengeService {
    private static final Logger logger = LogManager.getLogger(ChallengeServiceImpl.class);

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private SubmissionRepository submissionRepository;


    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Override
    public Optional<Challenge> getChallengeById(Long challengeId) throws ChallengeSphereException {
        logger.info("Fetching challenge by id: {}", challengeId);
        
        try {
            // Try cache first
            Optional<Challenge> cached = cacheService.getCachedChallenge(challengeId, Challenge.class);
            if (cached.isPresent()) {
                logger.debug("Cache hit for challenge id: {}", challengeId);
                return cached;
            }
            
            // Fetch from database
            logger.debug("Cache miss for challenge id: {}, fetching from DB", challengeId);
            Optional<Challenge> challenge = challengeRepository.findById(challengeId);
            
            // Cache if found
            challenge.ifPresent(c -> {
                cacheService.cacheChallenge(c.getId(), c);
                // Increment view count
                cacheService.incrementChallengeViews(c.getId());
            });
            
            return challenge;
        } catch (Exception e) {
            logger.error("Error fetching challenge by id {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public UserChallengesResponse getUserChallenges(GetUserChallengesRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();
        Boolean activeFilter = request.getActive();

        logger.info("Getting challenges for userId: {}, page: {}, pageSize: {}, activeFilter: {}",
                userId, page, pageSize, activeFilter);

        try {
            // Try cache first
//            Optional<UserChallengesResponse> cached = cacheService.getCachedUserChallenges(userId, page, pageSize, activeFilter, UserChallengesResponse.class);
//            if (cached.isPresent()) {
//                logger.info("Cache hit for user challenges: userId={}, page={}", userId, page);
//                return cached.get();
//            }
//            logger.debug("Cache miss for user challenges: userId={}, fetching from DB", userId);

            List<Challenge> createdChallenges = challengeRepository.findByCreatorId(userId);
            List<Challenge> ownedChallenges = challengeRepository.findByCurrentOwnerId(userId);

            Set<Challenge> allUserChallengesSet = new HashSet<>(createdChallenges);
            allUserChallengesSet.addAll(ownedChallenges);
            List<Challenge> allUserChallenges = new ArrayList<>(allUserChallengesSet);

            if (activeFilter != null) {
                allUserChallenges = allUserChallenges.stream()
                        .filter(challenge -> challenge.getActive().equals(activeFilter))
                        .toList();
            }

            int totalCount = allUserChallenges.size();
            int offset = (page - 1) * pageSize;
            List<Challenge> paginatedChallenges = allUserChallenges.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            logger.info("Found {} challenges for userId: {} (page {}/{})",
                    paginatedChallenges.size(), userId, page, (int) Math.ceil((double) totalCount / pageSize));

            UserChallengesResponse response = UserChallengesResponse.create(userId, paginatedChallenges, page, pageSize, totalCount);

            // Cache the response asynchronously
//            cacheService.cacheUserChallenges(userId, page, pageSize, activeFilter, response);

            return response;

        } catch (Exception e) {
            logger.error("Error getting challenges for userId: {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get user challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException {
        logger.info("Creating challenge with title: {} for creator userId: {}", request.getTitle(), request.getCreatorUserId());

        try {
            // Validate that the creator user exists
            if (!userRepository.existsById(request.getCreatorUserId())) {
                throw new ChallengeSphereException(
                        "Creator user not found with id: " + request.getCreatorUserId(),
                        HttpStatus.NOT_FOUND,
                        logger
                );
            }

            Challenge challenge = new Challenge();
            challenge.setTitle(request.getTitle());
            challenge.setDescription(request.getDescription());
            challenge.setHashtags(request.getHashtags() != null ? request.getHashtags() : "");
            challenge.setCategories(request.getCategories() != null ? request.getCategories() : "");
            challenge.setBenefits(request.getBenefits() != null ? request.getBenefits() : "");
            challenge.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);
            challenge.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
            challenge.setCreatorId(request.getCreatorUserId());
            challenge.setCurrentOwnerId(request.getCreatorUserId());
            challenge.setActive(true);
            challenge.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            challenge.setNumberOfDays(request.getNumberOfDays());
            if (request.getCoverPhotoUrls() != null && !request.getCoverPhotoUrls().isEmpty()) {
                challenge.setCoverPhotoUrls(String.join(",", request.getCoverPhotoUrls()));
            }

            Challenge savedChallenge = challengeRepository.save(challenge);
            logger.info("Successfully created challenge with id: {} for user: {}", savedChallenge.getId(), request.getCreatorUserId());

            // Cache the newly created challenge
            cacheService.cacheChallenge(savedChallenge.getId(), savedChallenge);
            
            // Invalidate recent/trending challenges cache since we have new content
            cacheService.invalidateRecentChallenges();
            cacheService.invalidateTrendingChallenges();

            // Invalidate user challenges cache since user created a new challenge
            cacheService.invalidateUserChallenges(request.getCreatorUserId());

            // Invalidate hashtag challenges cache since new challenge may match hashtag searches
            cacheService.invalidateHashtagChallenges();

            // Update user's challengesCreated count asynchronously
            updateChallengesCreatedCountAsync(request.getCreatorUserId());

            // Return success response
            return ChallengeCreateResponse.success(
                    savedChallenge.getId(),
                    savedChallenge.getTitle(),
                    savedChallenge.getDescription(),
                    savedChallenge.getHashtags(),
                    savedChallenge.getCategories(),
                    savedChallenge.getBenefits(),
                    savedChallenge.getIsPrivate(),
                    savedChallenge.getStartDate(),
                    savedChallenge.getActive(),
                    savedChallenge.getCreatedAt(),
                    savedChallenge.getCreatorId(),
                    savedChallenge.getCurrentOwnerId(),
                    savedChallenge.getNumberOfDays(),
                    savedChallenge.getCoverPhotoUrls()
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error creating challenge for creator userId: {}: {}", request.getCreatorUserId(), e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to create challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    /**
     * Asynchronously update the challengesCreated count for a user
     */
    @Async
    @Transactional
    protected void updateChallengesCreatedCountAsync(Long userId) {
        try {
            logger.info("Async update challengesCreated count for user: {}", userId);
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                Integer currentCount = user.getChallengesCreated() != null ? user.getChallengesCreated() : 0;
                user.setChallengesCreated(currentCount + 1);
                userRepository.save(user);
                // Update cached user profile with new count
                cacheService.cacheUserProfile(userId, user);
                logger.info("Updated challengesCreated count to {} for user: {}", currentCount + 1, userId);
            }
        } catch (Exception e) {
            logger.error("Error updating challengesCreated count for user {}: {}", userId, e.getMessage(), e);
        }
    }

    @Override
    public List<Challenge> getChallengeByIds(List<Long> challengeIds) throws ChallengeSphereException {
        try {
            logger.info("Fetching challenges by ids: {}", challengeIds);
            
            List<Challenge> result = new ArrayList<>();
            List<Long> cacheMisses = new ArrayList<>();
            
            // Try to get challenges from cache first
            for (Long challengeId : challengeIds) {
                Optional<Challenge> cached = cacheService.getCachedChallenge(challengeId, Challenge.class);
                if (cached.isPresent()) {
                    result.add(cached.get());
                    logger.debug("Cache hit for challenge id: {}", challengeId);
                } else {
                    cacheMisses.add(challengeId);
                }
            }
            
            // Fetch cache misses from database
            if (!cacheMisses.isEmpty()) {
                logger.debug("Cache misses for challenge ids: {}", cacheMisses);
                List<Challenge> dbChallenges = challengeRepository.findAllById(cacheMisses);
                
                // Cache the fetched challenges and add to result
                for (Challenge challenge : dbChallenges) {
                    cacheService.cacheChallenge(challenge.getId(), challenge);
                    result.add(challenge);
                }
            }
            
            logger.info("Retrieved {} challenges ({} from cache, {} from DB)", 
                result.size(), challengeIds.size() - cacheMisses.size(), cacheMisses.size());
            return result;
        } catch (Exception e) {
            logger.error("Error fetching challenges by ids {}: {}", challengeIds, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch challenges by ids",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public void deleteChallenges(List<Long> challengeIds) {
        try {
            logger.info("Bulk update on challenges {}", challengeIds);

            List<Challenge> challenges = challengeRepository.findAllById(challengeIds).stream().toList();
            for (Challenge challenge : challenges) {
                challenge.setActive(false);
                // Invalidate individual challenge cache
                cacheService.invalidateChallenge(challenge.getId());
            }
            challengeRepository.saveAll(challenges);
            
            // Invalidate list caches since challenge status changed
            cacheService.invalidateRecentChallenges();
            cacheService.invalidateTrendingChallenges();
            cacheService.invalidateHashtagChallenges();

            // Invalidate user challenges cache for affected creators/owners
            challenges.stream()
                    .map(Challenge::getCreatorId)
                    .distinct()
                    .forEach(cacheService::invalidateUserChallenges);

            // Invalidate participants cache for affected challenges
            challengeIds.forEach(cacheService::invalidateChallengeParticipants);

            // Invalidate feed caches since challenge content changed
            cacheService.invalidateAllFeeds();

            logger.info("Deleted {} challenges and invalidated caches", challenges.size());
        } catch (Exception e) {
            logger.error("Error during bulk update for challenges {}: {}", challengeIds, e.getMessage(), e);
            throw new RuntimeException("Failed to perform bulk update on challenges", e);
        }
    }

    @Override
    @Transactional
    public void disableChallengesForUsers(List<Long> userIds) throws ChallengeSphereException {
        try {
            logger.info("Disabling all challenges for userIds: {}", userIds);

            List<Challenge> challenges = challengeRepository.findByCreatorIdInOrCurrentOwnerIdIn(userIds, userIds);

            if (challenges.isEmpty()) {
                logger.info("No challenges found for userIds: {}", userIds);
                return;
            }

            for (Challenge challenge : challenges) {
                challenge.setActive(false);
                // Invalidate individual challenge cache
                cacheService.invalidateChallenge(challenge.getId());
            }

            challengeRepository.saveAll(challenges);
            
            // Invalidate list caches
            cacheService.invalidateRecentChallenges();
            cacheService.invalidateTrendingChallenges();
            cacheService.invalidateHashtagChallenges();

            // Invalidate user challenges cache for all affected users
            userIds.forEach(cacheService::invalidateUserChallenges);

            // Invalidate participants cache for affected challenges
            challenges.stream()
                    .map(Challenge::getId)
                    .forEach(cacheService::invalidateChallengeParticipants);

            // Invalidate feed caches
            cacheService.invalidateAllFeeds();

            logger.info("Disabled {} challenges for {} users and invalidated caches", challenges.size(), userIds.size());

        } catch (Exception e) {
            logger.error("Error disabling challenges for userIds {}.", userIds, e);
            throw new ChallengeSphereException(
                    "Failed to disable challenges for users",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public JoinChallengeResponse joinChallenge(JoinChallengeRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        List<Long> userIds = request.getUserIds();

        logger.info("Processing join request for challenge {} with {} user(s)", challengeId, userIds.size());

        try {
            // Validate that the challenge exists
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            // Batch validation: Check which users exist (single query)
            List<Long> existingUserIds = userRepository.findAllById(userIds).stream()
                    .map(User::getId)
                    .toList();
            Set<Long> existingUserIdSet = new HashSet<>(existingUserIds);

            // Find non-existent users
            List<Long> nonExistentUserIds = userIds.stream()
                    .filter(userId -> !existingUserIdSet.contains(userId))
                    .collect(Collectors.toList());

            if (!nonExistentUserIds.isEmpty()) {
                logger.warn("Users not found with ids: {}", nonExistentUserIds);
            }

            // Batch check: Find existing UserChallenge associations (single query)
            List<UserChallenge> existingAssociations = userChallengeRepository.findByChallengeId(challengeId);
            Set<Long> alreadyJoinedUserIds = existingAssociations.stream()
                    .map(UserChallenge::getUserId)
                    .filter(userIds::contains)
                    .collect(Collectors.toSet());

            if (!alreadyJoinedUserIds.isEmpty()) {
                logger.info("Users {} are already members of challenge {}", alreadyJoinedUserIds, challengeId);
            }

            // Determine which users can successfully join
            List<Long> successfulUserIds = existingUserIds.stream()
                    .filter(userId -> !alreadyJoinedUserIds.contains(userId))
                    .collect(Collectors.toList());

            // Determine failed users (non-existent + already joined)
            List<Long> failedUserIds = new ArrayList<>();
            failedUserIds.addAll(nonExistentUserIds);
            failedUserIds.addAll(alreadyJoinedUserIds);

            // Batch insert: Create all UserChallenge associations at once
            if (!successfulUserIds.isEmpty()) {
                Timestamp now = new Timestamp(System.currentTimeMillis());
                List<UserChallenge> userChallenges = successfulUserIds.stream()
                        .map(userId -> {
                            UserChallenge userChallenge = new UserChallenge();
                            userChallenge.setUserId(userId);
                            userChallenge.setChallengeId(challengeId);
                            userChallenge.setJoinedAt(now);
                            userChallenge.setActive(true);
                            userChallenge.setCompleted(false);
                            return userChallenge;
                        })
                        .collect(Collectors.toList());

                userChallengeRepository.saveAll(userChallenges);
                logger.info("Successfully joined {} users to challenge {}", successfulUserIds.size(), challengeId);
                
                // Track engagement: joining a challenge is high engagement (5 points per user)
                cacheService.trackChallengeEngagement(challengeId, successfulUserIds.size() * 5);
                
                // Invalidate trending challenges cache since participant counts changed
                cacheService.invalidateTrendingChallenges();

                // Invalidate participants cache for this challenge
                cacheService.invalidateChallengeParticipants(challengeId);

                // Invalidate user challenges cache for users who joined
                successfulUserIds.forEach(cacheService::invalidateUserChallenges);

                // Invalidate feed caches since new participants may affect feeds
                cacheService.invalidateFeed("FOLLOWING");
            }

            // Return appropriate response based on results
            if (successfulUserIds.isEmpty()) {
                return JoinChallengeResponse.failure(challengeId, "Failed to join any users to the challenge");
            } else if (failedUserIds.isEmpty()) {
                return JoinChallengeResponse.success(challengeId, challenge.getTitle(), successfulUserIds);
            } else {
                return JoinChallengeResponse.partial(challengeId, challenge.getTitle(), successfulUserIds, failedUserIds);
            }

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error processing join request for challenge {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to join challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public LeaveChallengeResponse leaveChallenge(LeaveChallengeRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        List<Long> userIds = request.getUserIds();

        logger.info("Processing leave request for challenge {} with {} user(s)", challengeId, userIds.size());

        try {
            // Validate that the challenge exists
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            // Batch query: Find existing UserChallenge associations for these users (single query)
            List<UserChallenge> existingAssociations = userChallengeRepository.findByChallengeId(challengeId);

            // Filter to only associations for the requested users
            Map<Long, UserChallenge> userChallengeMap = existingAssociations.stream()
                    .filter(uc -> userIds.contains(uc.getUserId()))
                    .collect(Collectors.toMap(UserChallenge::getUserId, uc -> uc));

            // Determine which users successfully have associations to deactivate
            List<Long> successfulUserIds = new ArrayList<>();
            List<Long> failedUserIds = new ArrayList<>();
            List<UserChallenge> toDeactivate = new ArrayList<>();

            for (Long userId : userIds) {
                if (userChallengeMap.containsKey(userId)) {
                    UserChallenge userChallenge = userChallengeMap.get(userId);
                    // Only deactivate if currently active
                    if (userChallenge.getActive()) {
                        successfulUserIds.add(userId);
                        userChallenge.setActive(false);
                        toDeactivate.add(userChallenge);
                    } else {
                        // User was already inactive
                        failedUserIds.add(userId);
                    }
                } else {
                    // User was not a member of the challenge
                    failedUserIds.add(userId);
                }
            }

            if (!failedUserIds.isEmpty()) {
                logger.info("Users {} were not active members of challenge {}", failedUserIds, challengeId);
            }

            // Batch update: Set all UserChallenge associations to inactive at once
            if (!toDeactivate.isEmpty()) {
                userChallengeRepository.saveAll(toDeactivate);
                logger.info("Successfully deactivated {} users from challenge {}", successfulUserIds.size(), challengeId);
                
                // Invalidate trending challenges cache since participant counts changed
//                cacheService.invalidateTrendingChallenges();

                // Invalidate participants cache for this challenge
//                cacheService.invalidateChallengeParticipants(challengeId);

                // Invalidate user challenges cache for users who left
                successfulUserIds.forEach(cacheService::invalidateUserChallenges);

                // Invalidate feed caches
//                cacheService.invalidateFeed("FOLLOWING");
            }

            // Return appropriate response based on results
            if (successfulUserIds.isEmpty()) {
                return LeaveChallengeResponse.failure(challengeId, "None of the users were active members of the challenge");
            } else if (failedUserIds.isEmpty()) {
                return LeaveChallengeResponse.success(challengeId, challenge.getTitle(), successfulUserIds);
            } else {
                return LeaveChallengeResponse.partial(challengeId, challenge.getTitle(), successfulUserIds, failedUserIds);
            }
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error processing leave request for challenge {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to leave challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public ChallengeParticipantsResponse getChallengeParticipants(GetChallengeParticipantsRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();

        logger.info("Getting active participants for challenge {}", challengeId);

        try {
            // Try cache first
            Optional<ChallengeParticipantsResponse> cached = cacheService.getCachedChallengeParticipants(challengeId, ChallengeParticipantsResponse.class);
            if (cached.isPresent()) {
                logger.info("Cache hit for challenge participants: challengeId={}", challengeId);
                return cached.get();
            }
            logger.debug("Cache miss for challenge participants: challengeId={}, fetching from DB", challengeId);

            List<UserChallenge> activeUserChallenges = userChallengeRepository.findActiveByChallengeId(challengeId);

            if (activeUserChallenges.isEmpty()) {
                logger.info("No active participants found for challenge: {}", challengeId);
                return ChallengeParticipantsResponse.create(challengeId, new ArrayList<>());
            }

            // Extract all unique user IDs
            List<Long> userIds = activeUserChallenges.stream()
                    .map(UserChallenge::getUserId)
                    .distinct()
                    .collect(Collectors.toList());

            // Batch fetch all users (single query)
            List<User> users = userRepository.findAllById(userIds);

            logger.info("Found {} active participants for challenge {}", users.size(), challengeId);

            ChallengeParticipantsResponse response = ChallengeParticipantsResponse.create(challengeId, users);

            // Cache the response asynchronously
            cacheService.cacheChallengeParticipants(challengeId, response);

            return response;

        } catch (Exception e) {
            logger.error("Error fetching participants for challenge {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch challenge participants",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengesByHashtagResponse getChallengesByHashtag(GetChallengesByHashtagRequest request) throws ChallengeSphereException {
        List<String> hashtags = request.getHashtags();
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting challenges by hashtags: {}, page: {}, pageSize: {}", hashtags, page, pageSize);

        try {
            // Try cache first
            Optional<ChallengesByHashtagResponse> cached = cacheService.getCachedHashtagChallenges(hashtags, page, pageSize, ChallengesByHashtagResponse.class);
            if (cached.isPresent()) {
                logger.info("Cache hit for hashtag challenges: hashtags={}, page={}", hashtags, page);
                return cached.get();
            }
            logger.debug("Cache miss for hashtag challenges: hashtags={}, fetching from DB", hashtags);

            // Batch query: Get all active challenges matching any of the hashtags (single query per hashtag)
            List<Challenge> matchingChallenges = hashtags.stream()
                    .flatMap(hashtag -> challengeRepository.findByHashtagsContaining(hashtag).stream())
                    .filter(Challenge::getActive)
                    .distinct()
                    .toList();

            if (matchingChallenges.isEmpty()) {
                logger.info("No active challenges found for hashtags: {}", hashtags);
                return ChallengesByHashtagResponse.create(hashtags, new ArrayList<>(), page, pageSize, 0);
            }

            // Extract challenge IDs
            List<Long> challengeIds = matchingChallenges.stream()
                    .map(Challenge::getId)
                    .collect(Collectors.toList());

            // Batch query: Get all active UserChallenge records for these challenges (single query)
            List<UserChallenge> activeUserChallenges = userChallengeRepository.findActiveByChallengeIds(challengeIds);

            // Count active participants per challenge
            Map<Long, Long> participantCountMap = activeUserChallenges.stream()
                    .collect(Collectors.groupingBy(UserChallenge::getChallengeId, Collectors.counting()));

            // Create challenge with participant count objects and sort by count (descending)
            List<ChallengesByHashtagResponse.ChallengeWithParticipantCount> challengeWithCounts = matchingChallenges.stream()
                    .map(challenge -> new ChallengesByHashtagResponse.ChallengeWithParticipantCount(
                            challenge,
                            participantCountMap.getOrDefault(challenge.getId(), 0L).intValue()
                    ))
                    .sorted((c1, c2) -> Integer.compare(c2.getActiveParticipantCount(), c1.getActiveParticipantCount()))
                    .toList();

            // Apply pagination
            int totalCount = challengeWithCounts.size();
            int offset = (page - 1) * pageSize;
            List<ChallengesByHashtagResponse.ChallengeWithParticipantCount> paginatedChallenges = challengeWithCounts.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            logger.info("Found {} challenges for hashtags '{}' (showing page {}/{})",
                    totalCount, hashtags, page, (int) Math.ceil((double) totalCount / pageSize));

            ChallengesByHashtagResponse response = ChallengesByHashtagResponse.create(hashtags, paginatedChallenges, page, pageSize, totalCount);

            // Cache the response asynchronously
            cacheService.cacheHashtagChallenges(hashtags, page, pageSize, response);

            return response;

        } catch (Exception e) {
            logger.error("Error fetching challenges by hashtags {}: {}", hashtags, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch challenges by hashtag",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PopularChallengesResponse getPopularChallenges(GetPopularChallengesRequest request) throws ChallengeSphereException {
        Boolean activeFilter = request.getActive();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting popular challenges - active: {}, startDate: {}, endDate: {}, page: {}, pageSize: {}",
                activeFilter, startDate, endDate, page, pageSize);

        try {
            // For default queries (active=true, no date filter, page 1), try cache first
            boolean isDefaultQuery = Boolean.TRUE.equals(activeFilter) 
                    && startDate == null 
                    && endDate == null 
                    && page == 1;
            
            if (isDefaultQuery) {
                Optional<PopularChallengesResponse> cached = cacheService.getTrendingChallenges(PopularChallengesResponse.class);
                if (cached.isPresent()) {
                    logger.info("Returning popular challenges from cache");
                    return cached.get();
                }
            }

            // Get all public challenges
            List<Challenge> publicChallenges = challengeRepository.findAll().stream()
                    .filter(challenge -> !challenge.getIsPrivate())
                    .collect(Collectors.toList());

            // Apply active filter if specified
            if (activeFilter != null) {
                publicChallenges = publicChallenges.stream()
                        .filter(challenge -> challenge.getActive().equals(activeFilter))
                        .collect(Collectors.toList());
            }

            // Apply date range filter if specified
            if (startDate != null || endDate != null) {
                publicChallenges = publicChallenges.stream()
                        .filter(challenge -> {
                            LocalDate challengeStartDate = challenge.getStartDate();
                            if (challengeStartDate == null) {
                                return false;
                            }
                            boolean afterStart = startDate == null || !challengeStartDate.isBefore(startDate);
                            boolean beforeEnd = endDate == null || !challengeStartDate.isAfter(endDate);
                            return afterStart && beforeEnd;
                        })
                        .collect(Collectors.toList());
            }

            if (publicChallenges.isEmpty()) {
                logger.info("No public challenges found matching the criteria");
                return PopularChallengesResponse.create(new ArrayList<>(), page, pageSize, 0);
            }

            // Extract challenge IDs
            List<Long> challengeIds = publicChallenges.stream()
                    .map(Challenge::getId)
                    .collect(Collectors.toList());

            // Batch query: Get all UserChallenge records for these challenges (single query)
            List<UserChallenge> userChallenges = userChallengeRepository.findByChallengeIdIn(challengeIds);

            // Count participants per challenge (all participants, not just active ones)
            Map<Long, Long> participantCountMap = userChallenges.stream()
                    .collect(Collectors.groupingBy(UserChallenge::getChallengeId, Collectors.counting()));

            // Create challenge with participant count objects and sort by count (descending)
            List<PopularChallengesResponse.ChallengeWithParticipantCount> challengeWithCounts = publicChallenges.stream()
                    .map(challenge -> new PopularChallengesResponse.ChallengeWithParticipantCount(
                            challenge,
                            participantCountMap.getOrDefault(challenge.getId(), 0L).intValue()
                    ))
                    .sorted((c1, c2) -> Integer.compare(c2.getParticipantCount(), c1.getParticipantCount()))
                    .toList();

            // Apply pagination
            int totalCount = challengeWithCounts.size();
            int offset = (page - 1) * pageSize;
            List<PopularChallengesResponse.ChallengeWithParticipantCount> paginatedChallenges = challengeWithCounts.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            logger.info("Found {} public challenges matching criteria (showing page {}/{})",
                    totalCount, page, (int) Math.ceil((double) totalCount / pageSize));

            PopularChallengesResponse response = PopularChallengesResponse.create(paginatedChallenges, page, pageSize, totalCount);
            
            // Cache the response for default queries
            if (isDefaultQuery) {
                cacheService.cacheTrendingChallenges(response);
                logger.debug("Cached popular challenges response");
            }
            
            return response;

        } catch (Exception e) {
            logger.error("Error fetching popular challenges: {}", e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch popular challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    // ==================== RECENT CHALLENGES WITH CACHING ====================

    @Override
    @Transactional(readOnly = true)
    public RecentChallengesResponse getRecentChallenges(int page, int pageSize) throws ChallengeSphereException {
        logger.info("Getting recent challenges - page: {}, pageSize: {}", page, pageSize);

        try {
            // Validate pagination parameters
            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 10;
            if (pageSize > 100) pageSize = 100; // Cap at 100

            // Try cache first
            Optional<RecentChallengesResponse> cached = cacheService.getRecentChallengesPaginated(page, pageSize, RecentChallengesResponse.class);
            if (cached.isPresent()) {
                logger.debug("Cache hit for recent challenges page {}", page);
                return cached.get();
            }

            // Cache miss - fetch from database
            logger.debug("Cache miss for recent challenges page {}, fetching from DB", page);

            // Get total count for pagination
            long totalCount = challengeRepository.countPublicActiveChallenges();
            
            if (totalCount == 0) {
                return RecentChallengesResponse.empty(page, pageSize);
            }

            // Fetch paginated challenges from DB
            int offset = (page - 1) * pageSize;
            List<Challenge> challenges = challengeRepository.findRecentPublicChallengesPaginated(pageSize, offset);

            // Get participant counts for these challenges
            List<Long> challengeIds = challenges.stream()
                    .map(Challenge::getId)
                    .toList();
            
            Map<Long, Long> participantCountMap = new java.util.HashMap<>();
            if (!challengeIds.isEmpty()) {
                List<UserChallenge> userChallenges = userChallengeRepository.findByChallengeIdIn(challengeIds);
                participantCountMap = userChallenges.stream()
                        .collect(Collectors.groupingBy(UserChallenge::getChallengeId, Collectors.counting()));
            }

            // Convert to response with engagement metrics from Redis
            final Map<Long, Long> finalParticipantMap = participantCountMap;
            List<RecentChallengesResponse.ChallengeInfo> challengeInfos = challenges.stream()
                    .map(challenge -> {
                        long viewCount = cacheService.getChallengeViews(challenge.getId());
                        long engagementScore = cacheService.getChallengeEngagementScore(challenge.getId());
                        int participantCount = finalParticipantMap.getOrDefault(challenge.getId(), 0L).intValue();
                        return RecentChallengesResponse.ChallengeInfo.fromChallenge(
                                challenge, viewCount, engagementScore, participantCount);
                    })
                    .toList();

            RecentChallengesResponse response = RecentChallengesResponse.create(
                    challengeInfos, page, pageSize, (int) totalCount);

            // Cache the response
            cacheService.cacheRecentChallengesPaginated(page, pageSize, response);
            logger.debug("Cached recent challenges page {}", page);

            return response;

        } catch (Exception e) {
            logger.error("Error fetching recent challenges: {}", e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch recent challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public List<Long> getTrendingChallengeIds(int limit) {
        logger.info("Getting trending challenge IDs, limit: {}", limit);
        
        if (limit < 1) limit = 10;
        if (limit > 100) limit = 100;
        
        // Try engagement-based trending first
        List<Long> engagedIds = cacheService.getTopEngagedChallengeIds(limit);
        if (!engagedIds.isEmpty()) {
            logger.debug("Returning {} trending challenges by engagement", engagedIds.size());
            return engagedIds;
        }
        
        // Fallback to view-based trending
        List<Long> viewedIds = cacheService.getTopViewedChallengeIds(limit);
        if (!viewedIds.isEmpty()) {
            logger.debug("Returning {} trending challenges by views", viewedIds.size());
            return viewedIds;
        }
        
        // Fallback to empty list (no engagement data yet)
        logger.debug("No trending data available, returning empty list");
        return List.of();
    }

    @Override
    @Transactional
    public Challenge updateChallenge(Long id, ChallengeUpdateRequest request) throws ChallengeSphereException {
        logger.info("Updating challenge id: {}", id);

        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + id,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            challenge.setTitle(request.getTitle());
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            challenge.setDescription(request.getDescription());
        }
        if (request.getNumberOfDays() != null) {
            challenge.setNumberOfDays(request.getNumberOfDays());
        }
        if (request.getActive() != null) {
            challenge.setActive(request.getActive());
        }
        if (request.getCoverPhotoUrls() != null) {
            challenge.setCoverPhotoUrls(request.getCoverPhotoUrls().isEmpty()
                    ? null
                    : String.join(",", request.getCoverPhotoUrls()));
        }

        Challenge updated = challengeRepository.save(challenge);
        cacheService.cacheChallenge(id, updated);
        logger.info("Challenge {} updated successfully", id);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengeDetailResponse getChallengeDetail(Long challengeId, Long currentUserId) throws ChallengeSphereException {
        logger.info("Fetching challenge detail: challengeId={}, currentUserId={}", challengeId, currentUserId);
        try {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND, logger));

            int participantCount = userChallengeRepository.countActiveByChallengeId(challengeId);

            boolean isJoined = false;
            if (currentUserId != null) {
                isJoined = userChallengeRepository.findByUserIdAndChallengeId(currentUserId, challengeId)
                        .map(uc -> Boolean.TRUE.equals(uc.getActive()))
                        .orElse(false);
            }

            return ChallengeDetailResponse.from(challenge, participantCount, isJoined);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching challenge detail for id {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException("Failed to fetch challenge detail", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengeProgressResponse getChallengeProgress(Long challengeId, Long userId) throws ChallengeSphereException {
        logger.info("Fetching challenge progress: challengeId={}, userId={}", challengeId, userId);
        try {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND, logger));

            List<com.ironside.challengesphere.model.Submission> submissions =
                    submissionRepository.findActiveByUserIdAndChallengeId(userId, challengeId);

            Map<Integer, com.ironside.challengesphere.model.Submission> byDay = submissions.stream()
                    .collect(Collectors.toMap(
                            com.ironside.challengesphere.model.Submission::getDayNumber,
                            s -> s,
                            (a, b) -> a // keep first if duplicate day
                    ));

            int totalDays = challenge.getNumberOfDays();
            List<ChallengeProgressResponse.DayProgress> days = new ArrayList<>();
            for (int d = 1; d <= totalDays; d++) {
                com.ironside.challengesphere.model.Submission s = byDay.get(d);
                if (s != null) {
                    String thumbnail = firstUrl(s.getMediaUrls());
                    days.add(ChallengeProgressResponse.DayProgress.submitted(
                            d, s.getId(), thumbnail, s.getGloriesCount(), s.getSubmissionTime()));
                } else {
                    days.add(ChallengeProgressResponse.DayProgress.empty(d));
                }
            }

            List<Integer> submittedDayNumbers = new ArrayList<>(byDay.keySet());
            int currentStreak = computeCurrentStreak(submittedDayNumbers);
            int longestStreak = computeLongestStreak(submittedDayNumbers);
            int daysCompleted = submittedDayNumbers.size();
            int completionPct = totalDays > 0 ? (int) Math.round((daysCompleted * 100.0) / totalDays) : 0;

            ChallengeProgressResponse response = new ChallengeProgressResponse();
            response.setChallengeId(challengeId);
            response.setChallengeTitle(challenge.getTitle());
            response.setTotalDays(totalDays);
            response.setDaysCompleted(daysCompleted);
            response.setCompletionPercentage(completionPct);
            response.setCurrentStreak(currentStreak);
            response.setLongestStreak(longestStreak);
            response.setDays(days);
            return response;
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching progress: challengeId={}, userId={}: {}", challengeId, userId, e.getMessage(), e);
            throw new ChallengeSphereException("Failed to fetch challenge progress", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MyChallengesResponse getMyChallenges(Long userId, String status) throws ChallengeSphereException {
        logger.info("Fetching my challenges: userId={}, status={}", userId, status);
        try {
            List<UserChallenge> userChallenges = userChallengeRepository.findActiveByUserId(userId);
            boolean filterCompleted = "completed".equalsIgnoreCase(status);

            userChallenges = userChallenges.stream()
                    .filter(uc -> Boolean.TRUE.equals(uc.getCompleted()) == filterCompleted)
                    .toList();

            List<Long> challengeIds = userChallenges.stream().map(UserChallenge::getChallengeId).toList();
            Map<Long, Challenge> challengeMap = challengeRepository.findAllById(challengeIds)
                    .stream().collect(Collectors.toMap(Challenge::getId, c -> c));

            List<MyChallengesResponse.ChallengeSummary> summaries = new ArrayList<>();
            for (UserChallenge uc : userChallenges) {
                Challenge c = challengeMap.get(uc.getChallengeId());
                if (c == null) continue;

                List<com.ironside.challengesphere.model.Submission> subs =
                        submissionRepository.findActiveByUserIdAndChallengeId(userId, uc.getChallengeId());

                List<Integer> submittedDays = subs.stream()
                        .map(com.ironside.challengesphere.model.Submission::getDayNumber)
                        .collect(Collectors.toList());

                int daysCompleted = submittedDays.size();
                int totalDays = c.getNumberOfDays();
                int completionPct = totalDays > 0 ? (int) Math.round((daysCompleted * 100.0) / totalDays) : 0;

                MyChallengesResponse.ChallengeSummary summary = MyChallengesResponse.ChallengeSummary.from(c);
                summary.setDaysCompleted(daysCompleted);
                summary.setCurrentStreak(computeCurrentStreak(submittedDays));
                summary.setCompletionPercentage(completionPct);
                summary.setJoinedAt(uc.getJoinedAt());
                summary.setCompleted(Boolean.TRUE.equals(uc.getCompleted()));
                summaries.add(summary);
            }

            return MyChallengesResponse.create(summaries);
        } catch (Exception e) {
            logger.error("Error fetching my challenges for userId={}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException("Failed to fetch my challenges", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    public PresignedUrlsResponse generateChallengePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException {
        logger.info("Generating challenge presigned URLs for userId={}, files={}", request.getUserId(), request.getMediaFiles().size());
        try {
            if (!userRepository.existsById(request.getUserId())) {
                throw new ChallengeSphereException("User not found with id: " + request.getUserId(), HttpStatus.NOT_FOUND, logger);
            }
            if (request.getMediaFiles().size() > 5) {
                throw new ChallengeSphereException("A challenge can have at most 5 cover photos", HttpStatus.BAD_REQUEST, logger);
            }

            long expiry = 3600L;
            List<PresignedUrlsResponse.PresignedUrlInfo> urls = new ArrayList<>();
            for (GeneratePresignedUrlsRequest.MediaFileInfo file : request.getMediaFiles()) {
                String ext = getFileExtension(file.getFileName());
                String key = String.format("challenges/user_%d/covers/%d_%s%s",
                        request.getUserId(), System.currentTimeMillis(), java.util.UUID.randomUUID(), ext);
                urls.add(new PresignedUrlsResponse.PresignedUrlInfo(
                        file.getFileName(),
                        file.getMediaType().name(),
                        file.getContentType().getMimeType(),
                        com.ironside.challengesphere.util.GetSignedUrlForGcs.generateV4PutObjectSignedUrl(
                                com.ironside.challengesphere.util.Constants.GCSBUCKETFORSUBMISSIONS,
                                key, expiry, file.getContentType()),
                        key, expiry));
            }
            return PresignedUrlsResponse.success(request.getUserId(), urls);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error generating challenge presigned URLs for userId={}: {}", request.getUserId(), e.getMessage(), e);
            throw new ChallengeSphereException("Failed to generate presigned URLs", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    private String firstUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        return parts.length > 0 ? parts[0].trim() : null;
    }

    private int computeCurrentStreak(List<Integer> days) {
        if (days.isEmpty()) return 0;
        List<Integer> sorted = days.stream().sorted(java.util.Comparator.reverseOrder()).toList();
        int streak = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) == sorted.get(i - 1) - 1) streak++;
            else break;
        }
        return streak;
    }

    private int computeLongestStreak(List<Integer> days) {
        if (days.isEmpty()) return 0;
        List<Integer> sorted = days.stream().sorted().toList();
        int longest = 1, current = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) == sorted.get(i - 1) + 1) {
                longest = Math.max(longest, ++current);
            } else {
                current = 1;
            }
        }
        return longest;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
