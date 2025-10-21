package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.request.GetChallengeParticipantsRequest;
import com.ironside.challengesphere.api.request.GetChallengesByHashtagRequest;
import com.ironside.challengesphere.api.request.GetPopularChallengesRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.PopularChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.service.ChallengeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChallengeServiceImpl implements ChallengeService {
    private static final Logger logger = LogManager.getLogger(ChallengeServiceImpl.class);

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Override
    public UserChallengesResponse getUserChallenges(GetUserChallengesRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();
        Boolean activeFilter = request.getActive();

        logger.info("Getting challenges for userId: {}, page: {}, pageSize: {}, activeFilter: {}",
                userId, page, pageSize, activeFilter);

        try {
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

            return UserChallengesResponse.create(userId, paginatedChallenges, page, pageSize, totalCount);

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

            Challenge savedChallenge = challengeRepository.save(challenge);
            logger.info("Successfully created challenge with id: {} for user: {}", savedChallenge.getId(), request.getCreatorUserId());

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
                    savedChallenge.getNumberOfDays()
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
                logger.info("Updated challengesCreated count to {} for user: {}", currentCount + 1, userId);
            }
        } catch (Exception e) {
            logger.error("Error updating challengesCreated count for user {}: {}", userId, e.getMessage(), e);
        }
    }

    @Override
    public List<Challenge> getChallengeByIds(List<Long> challengeId) throws ChallengeSphereException {
        try {
            logger.info("Fetching challenges by ids: {}", challengeId);
            return challengeRepository.findAllById(challengeId).stream().toList();
        } catch (Exception e) {
            logger.error("Error fetching challenges by ids {}: {}", challengeId, e.getMessage(), e);
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
            for (Challenge challenge : challenges)
                challenge.setActive(false);
            challengeRepository.saveAll(challenges);
            logger.info("Deleted {} challenges", challenges.size());
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
            }

            challengeRepository.saveAll(challenges);
            logger.info("Disabled {} challenges for {} users", challenges.size(), userIds.size());

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

            return ChallengeParticipantsResponse.create(challengeId, users);

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

            return ChallengesByHashtagResponse.create(hashtags, paginatedChallenges, page, pageSize, totalCount);

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

            return PopularChallengesResponse.create(paginatedChallenges, page, pageSize, totalCount);

        } catch (Exception e) {
            logger.error("Error fetching popular challenges: {}", e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to fetch popular challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }
}
