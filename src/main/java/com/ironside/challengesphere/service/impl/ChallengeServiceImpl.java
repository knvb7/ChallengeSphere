package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import com.ironside.challengesphere.config.ChallengeParticipationProperties;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.IssuedUploadService;
import com.ironside.challengesphere.security.UploadQuotaService;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.util.GetSignedUrlForS3;

@Service
public class ChallengeServiceImpl implements ChallengeService {
    private static final Logger logger = LogManager.getLogger(ChallengeServiceImpl.class);
    private static final int MAX_CHALLENGE_COVER_PHOTOS = 5;
    private static final int MAX_CHALLENGES_CREATED_PER_DAY = 5;
    private static final int MAX_CHALLENGE_DURATION_DAYS = 365;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private ChallengeParticipationProperties challengeParticipationProperties;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private ChallengeMediaKeyPolicy challengeMediaKeyPolicy;

    @Autowired
    private ContentModerationService contentModerationService;

    @Autowired
    private UploadQuotaService uploadQuotaService;

    @Autowired
    private IssuedUploadService issuedUploadService;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Override
    public Optional<Challenge> getChallengeById(Long challengeId) throws ChallengeSphereException {
        logger.info("Fetching challenge by id: {}", challengeId);

        try {
            Optional<Challenge> cached = cacheService.getCachedChallenge(challengeId, Challenge.class);
            if (cached.isPresent()) {
                logger.debug("Cache hit for challenge id: {}", challengeId);
                return cached.map(this::withReadableCoverPhotoUrls);
            }

            logger.debug("Cache miss for challenge id: {}, fetching from DB", challengeId);
            Optional<Challenge> challenge = challengeRepository.findById(challengeId);

            challenge.ifPresent(c -> cacheService.cacheChallenge(c.getId(), c));

            return challenge.map(this::withReadableCoverPhotoUrls);
        } catch (Exception e) {
            logger.error("Error fetching challenge by id {}: {}", challengeId, e.getClass().getSimpleName());
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
        Long currentUserId = request.getCurrentUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();
        Boolean activeFilter = request.getActive();

        logger.info("Getting challenges for userId: {}, page: {}, pageSize: {}, activeFilter: {}",
                userId, page, pageSize, activeFilter);

        try {
            ensureFollowAccess(userId, currentUserId, "challenges");
            boolean selfView = userId != null && userId.equals(currentUserId);

            List<Challenge> createdChallenges = challengeRepository.findByCreatorId(userId);
            List<Challenge> ownedChallenges = challengeRepository.findByCurrentOwnerId(userId);
            List<Long> joinedChallengeIds = userChallengeRepository.findActiveByUserId(userId).stream()
                    .map(UserChallenge::getChallengeId)
                    .distinct()
                    .toList();
            List<Challenge> joinedChallenges = joinedChallengeIds.isEmpty()
                    ? List.of()
                    : challengeRepository.findAllById(joinedChallengeIds);

            Map<Long, Challenge> allUserChallengesById = new LinkedHashMap<>();
            createdChallenges.forEach(challenge -> allUserChallengesById.put(challenge.getId(), challenge));
            ownedChallenges.forEach(challenge -> allUserChallengesById.putIfAbsent(challenge.getId(), challenge));
            joinedChallenges.forEach(challenge -> allUserChallengesById.putIfAbsent(challenge.getId(), challenge));
            List<Challenge> allUserChallenges = new ArrayList<>(allUserChallengesById.values());

            if (!selfView) {
                allUserChallenges = allUserChallenges.stream()
                        .filter(challenge -> canViewChallenge(challenge, currentUserId))
                        .toList();
            }

            if (activeFilter != null) {
                allUserChallenges = allUserChallenges.stream()
                        .filter(challenge -> challenge.getActive().equals(activeFilter))
                        .toList();
            }

            allUserChallenges = allUserChallenges.stream()
                    .sorted(ChallengeServiceImpl::compareChallengesByCreatedAtDesc)
                    .toList();

            int totalCount = allUserChallenges.size();
            int offset = (page - 1) * pageSize;
            List<Challenge> paginatedChallenges = allUserChallenges.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            logger.info("Found {} challenges for userId: {} (page {}/{})",
                    paginatedChallenges.size(), userId, page, (int) Math.ceil((double) totalCount / pageSize));

            UserChallengesResponse response = UserChallengesResponse.create(
                    userId,
                    withReadableCoverPhotoUrls(paginatedChallenges),
                    page,
                    pageSize,
                    totalCount
            );

            return response;

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting challenges for userId: {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get user challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserChallengesResponse getPublicChallengesByCreator(Long creatorId, int page, int pageSize, Long currentUserId)
            throws ChallengeSphereException {
        logger.info("Getting public challenges for creatorId: {}, page: {}, pageSize: {}, viewer: {}",
                creatorId, page, pageSize, currentUserId);

        try {
            int normalizedPage = Math.max(page, 1);
            int normalizedPageSize = Math.min(Math.max(pageSize, 1), 100);
            List<Challenge> publicChallenges = challengeRepository.findPublicActiveByCreatorId(creatorId);
            int totalCount = publicChallenges.size();
            int offset = (normalizedPage - 1) * normalizedPageSize;
            List<Challenge> paginatedChallenges = publicChallenges.stream()
                    .skip(offset)
                    .limit(normalizedPageSize)
                    .toList();

            return UserChallengesResponse.create(
                    creatorId,
                    withReadableCoverPhotoUrls(paginatedChallenges),
                    normalizedPage,
                    normalizedPageSize,
                    totalCount
            );
        } catch (Exception e) {
            logger.error("Error getting public challenges for creatorId {}: {}", creatorId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get public creator challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException {
        logger.info("Creating challenge for creator userId={}", request.getCreatorUserId());

        try {
            ChallengeCreationContentPolicy.Content content =
                    ChallengeCreationContentPolicy.validateAndNormalize(request);

            // Lock the creator row so concurrent Swagger/API requests cannot race
            // past the per-day creation cap.
            userRepository.findByIdForUpdate(request.getCreatorUserId())
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Creator user not found with id: " + request.getCreatorUserId(),
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            enforceDailyChallengeCreationLimit(request.getCreatorUserId());

            Challenge challenge = new Challenge();
            challenge.setTitle(content.title());
            challenge.setDescription(content.storedDescription());
            challenge.setHashtags(request.getHashtags() != null ? request.getHashtags() : "");
            challenge.setCategories(content.categories());
            challenge.setBenefits(content.benefits());
            challenge.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);
            challenge.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
            challenge.setCreatorId(request.getCreatorUserId());
            challenge.setCurrentOwnerId(request.getCreatorUserId());
            challenge.setActive(true);
            challenge.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            String challengeMode = normalizeChallengeMode(request.getChallengeMode());
            challenge.setChallengeMode(challengeMode);
            challenge.setNumberOfDays(resolveChallengeDuration(request.getNumberOfDays(), challengeMode));
            List<String> requestedCoverPhotoUrls = getRequestedCoverPhotoUrls(request);
            if (requestedCoverPhotoUrls != null) {
                challenge.setCoverPhotoUrls(validateAndSerializeCoverPhotoUrls(
                        request.getCreatorUserId(), requestedCoverPhotoUrls, null));
            }

            Challenge savedChallenge = challengeRepository.save(challenge);
            UserChallenge creatorParticipant = new UserChallenge();
            creatorParticipant.setUserId(request.getCreatorUserId());
            creatorParticipant.setChallengeId(savedChallenge.getId());
            creatorParticipant.setJoinedAt(new Timestamp(System.currentTimeMillis()));
            creatorParticipant.setCompleted(false);
            creatorParticipant.setActive(true);
            userChallengeRepository.save(creatorParticipant);

            logger.info("Successfully created challenge with id: {} for user: {}", savedChallenge.getId(), request.getCreatorUserId());
            cacheService.cacheChallenge(savedChallenge.getId(), savedChallenge);
            cacheService.invalidateRecentAndTrendingChallenges();
            if (savedChallenge.getHashtags() != null && !savedChallenge.getHashtags().isBlank()) {
                cacheService.invalidateHashtagChallenges();
            }
            // Update user's challengesCreated count asynchronously
            updateChallengesCreatedCountAsync(request.getCreatorUserId());
            notificationService.notifyChallengeInvitesAsync(
                    savedChallenge,
                    request.getCreatorUserId(),
                    request.getInvitedUserIds()
            );

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
                    savedChallenge.getChallengeMode(),
                    toReadableCoverPhotoUrls(savedChallenge)
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error creating challenge for creator userId: {}: {}", request.getCreatorUserId(), e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to create challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    private void enforceDailyChallengeCreationLimit(Long creatorUserId) throws ChallengeSphereException {
        LocalDate today = LocalDate.now();
        Timestamp startOfDay = Timestamp.valueOf(today.atStartOfDay());
        Timestamp startOfNextDay = Timestamp.valueOf(today.plusDays(1).atStartOfDay());
        long createdToday = challengeRepository.countCreatedByCreatorIdBetween(
                creatorUserId,
                startOfDay,
                startOfNextDay
        );

        if (createdToday >= MAX_CHALLENGES_CREATED_PER_DAY) {
            throw new ChallengeSphereException(
                    "You can create only 5 challenges per day. Please try again tomorrow.",
                    HttpStatus.TOO_MANY_REQUESTS,
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
            userRepository.increaseChallengeCreatedCountForAUser(userId);
            logger.info("Updated challengesCreated count for userId: {}", userId);
        } catch (Exception e) {
            logger.error("Error updating challengesCreated count for user {}: {}", userId, e.getClass().getSimpleName());
        }
    }

    @Async
    @Transactional
    protected void updateChallengesAcceptedCountAsync(Long userId, boolean increment) {
        try {
            logger.info("Async update challengesAccepted count for user: {}, increment: {}", userId, increment);
            userRepository.adjustChallengeAcceptedCountForAUser(userId, increment ? 1 : -1);
            logger.info("Updated challengesAccepted count for userId: {}", userId);
        } catch (Exception e) {
            logger.error("Error updating challengesAccepted count for user {}: {}", userId, e.getClass().getSimpleName());
        }
    }

    @Override
    public List<Challenge> getChallengeByIds(List<Long> challengeIds, Long currentUserId) throws ChallengeSphereException {
        try {
            logger.info("Fetching challenges by ids: {}", challengeIds);

            List<Challenge> result = new ArrayList<>();
            List<Long> cacheMisses = new ArrayList<>();

            // Try to get challenges from cache first
            // for (Long challengeId : challengeIds) {
            //     Optional<Challenge> cached = cacheService.getCachedChallenge(challengeId, Challenge.class);
            //     if (cached.isPresent()) {
            //         result.add(cached.get());
            //         logger.debug("Cache hit for challenge id: {}", challengeId);
            //     } else {
            //         cacheMisses.add(challengeId);
            //     }
            // }

            // Fetch all from database
            // if (!cacheMisses.isEmpty()) {
            //     logger.debug("Cache misses for challenge ids: {}", cacheMisses);
            //     List<Challenge> dbChallenges = challengeRepository.findAllById(cacheMisses);
            //     for (Challenge challenge : dbChallenges) {
            //         cacheService.cacheChallenge(challenge.getId(), challenge);
            //         result.add(challenge);
            //     }
            // }
            cacheMisses.addAll(challengeIds);
            List<Challenge> dbChallenges = challengeRepository.findAllById(cacheMisses);
            result.addAll(dbChallenges.stream()
                    .filter(challenge -> canViewChallenge(challenge, currentUserId))
                    .toList());

            logger.info("Retrieved {} challenges from DB",
                    result.size());
            return withReadableCoverPhotoUrls(result);
        } catch (Exception e) {
            logger.error("Error fetching challenges by ids {}: {}", challengeIds, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to fetch challenges by ids",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public void deleteChallenge(Long challengeId, Long actorUserId) throws ChallengeSphereException {
        logger.info("Deleting challenge {} by actor {}", challengeId, actorUserId);

        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        boolean owner = actorUserId != null
                && (actorUserId.equals(challenge.getCreatorId()) || actorUserId.equals(challenge.getCurrentOwnerId()));
        if (!owner) {
            throw new ChallengeSphereException("Not authorized to delete this challenge", HttpStatus.FORBIDDEN, logger);
        }

        List<UserChallenge> allParticipantLinks = userChallengeRepository.findByChallengeIds(List.of(challengeId));
        if (Boolean.FALSE.equals(challenge.getActive())) {
            invalidateDeletedChallengeCaches(challenge, allParticipantLinks);
            logger.info("Challenge {} is already deleted; invalidated caches", challengeId);
            return;
        }

        challenge.setActive(false);
        challengeRepository.save(challenge);

        List<UserChallenge> participantLinks = allParticipantLinks.stream()
                .filter(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive()))
                .peek(userChallenge -> userChallenge.setActive(false))
                .toList();
        if (!participantLinks.isEmpty()) {
            userChallengeRepository.saveAll(participantLinks);
        }

        invalidateDeletedChallengeCaches(challenge, participantLinks);

        logger.info("Challenge {} deleted by actor {}", challengeId, actorUserId);
    }

    @Override
    @Transactional
    public void deleteChallenges(List<Long> challengeIds) {
        try {
            logger.info("Bulk update on challenges {}", challengeIds);

            List<Challenge> challenges = challengeRepository.findAllById(challengeIds).stream().toList();
            for (Challenge challenge : challenges) {
                challenge.setActive(false);
            }
            challengeRepository.saveAll(challenges);

            List<UserChallenge> allParticipantLinks = userChallengeRepository.findByChallengeIds(challengeIds);
            List<UserChallenge> participantLinks = allParticipantLinks.stream()
                    .filter(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive()))
                    .peek(userChallenge -> userChallenge.setActive(false))
                    .toList();
            if (!participantLinks.isEmpty()) {
                userChallengeRepository.saveAll(participantLinks);
            }

            Map<Long, List<UserChallenge>> participantsByChallengeId = allParticipantLinks.stream()
                    .collect(Collectors.groupingBy(UserChallenge::getChallengeId));
            challenges.forEach(challenge -> invalidateDeletedChallengeCaches(
                    challenge,
                    participantsByChallengeId.getOrDefault(challenge.getId(), List.of())
            ));

            logger.info("Deleted {} challenges and invalidated caches", challenges.size());
        } catch (Exception e) {
            logger.error("Error during bulk update for challenges {}: {}", challengeIds, e.getClass().getSimpleName());
            throw new RuntimeException("Failed to perform bulk update on challenges", e);
        }
    }

    private void invalidateDeletedChallengeCaches(Challenge challenge, List<UserChallenge> participantLinks) {
        if (challenge == null || challenge.getId() == null) {
            return;
        }

        Long challengeId = challenge.getId();
        cacheService.clearChallengeCache(challengeId);
        cacheService.invalidateChallengeParticipants(challengeId);
        cacheService.invalidateHashtagChallenges();
        cacheService.invalidateAllFeeds();

        Set<Long> affectedUserIds = new HashSet<>();
        if (challenge.getCreatorId() != null) {
            affectedUserIds.add(challenge.getCreatorId());
        }
        if (challenge.getCurrentOwnerId() != null) {
            affectedUserIds.add(challenge.getCurrentOwnerId());
        }
        if (participantLinks != null) {
            participantLinks.stream()
                    .map(UserChallenge::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(affectedUserIds::add);
        }
        affectedUserIds.forEach(cacheService::invalidateUserChallenges);
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
                cacheService.invalidateChallenge(challenge.getId());
            }

            challengeRepository.saveAll(challenges);

            // Invalidate list caches
            // cacheService.invalidateRecentChallenges();
            // cacheService.invalidateTrendingChallenges();
            // cacheService.invalidateHashtagChallenges();

            // Invalidate user challenges cache for all affected users
            // userIds.forEach(cacheService::invalidateUserChallenges);

            // Invalidate participants cache for affected challenges
            // challenges.stream()
            //         .map(Challenge::getId)
            //         .forEach(cacheService::invalidateChallengeParticipants);

            cacheService.invalidateAllFeeds();

            logger.info("Disabled {} challenges for {} users and invalidated caches", challenges.size(), userIds.size());

        } catch (Exception e) {
            logger.error("Error disabling challenges for userIds {}.", userIds);
            throw new ChallengeSphereException(
                    "Failed to disable challenges for users",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JoinChallengeResponse joinChallenge(JoinChallengeRequest request) throws ChallengeSphereException {
        return joinChallenge(request, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JoinChallengeResponse joinChallengeFromVerifiedInvite(JoinChallengeRequest request)
            throws ChallengeSphereException {
        return joinChallenge(request, true);
    }

    private JoinChallengeResponse joinChallenge(
            JoinChallengeRequest request,
            boolean verifiedInvitation) throws ChallengeSphereException {
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

            if (!Boolean.TRUE.equals(challenge.getActive())) {
                throw new ChallengeSphereException(
                        "Cannot join an inactive challenge",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            if (isExpiredChallenge(challenge)) {
                throw new ChallengeSphereException(
                        "This challenge has ended and is no longer accepting new participants",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            Long ownerUserId = challenge.getCurrentOwnerId() != null ? challenge.getCurrentOwnerId() : challenge.getCreatorId();
            for (Long userId : userIds) {
                if (!canJoinChallenge(challenge, ownerUserId, userId, verifiedInvitation)) {
                    throw new ChallengeSphereException(
                            "Not authorized to join this challenge",
                            HttpStatus.FORBIDDEN,
                            logger
                    );
                }
            }

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
            Map<Long, UserChallenge> existingByUserId = existingAssociations.stream()
                    .filter(userChallenge -> userIds.contains(userChallenge.getUserId()))
                    .collect(Collectors.toMap(UserChallenge::getUserId, userChallenge -> userChallenge, (a, b) -> a));

            boolean hasBannedParticipant = existingUserIds.stream()
                    .map(existingByUserId::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(userChallenge -> Boolean.TRUE.equals(userChallenge.getBanned()));
            if (hasBannedParticipant) {
                throw new ChallengeSphereException(
                        "You were removed from this challenge by its creator.",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            Set<Long> alreadyJoinedUserIds = existingByUserId.values().stream()
                    .filter(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive()))
                    .map(UserChallenge::getUserId)
                    .collect(Collectors.toSet());

            if (!alreadyJoinedUserIds.isEmpty()) {
                logger.info("Users {} are already members of challenge {}", alreadyJoinedUserIds, challengeId);
            }

            // Determine which users can successfully join
            List<Long> successfulUserIds = existingUserIds.stream()
                    .filter(userId -> !alreadyJoinedUserIds.contains(userId))
                    .collect(Collectors.toList());

            for (Long userId : successfulUserIds.stream().sorted().toList()) {
                userRepository.findByIdForUpdate(userId)
                        .orElseThrow(() -> new ChallengeSphereException(
                                "User not found with id: " + userId,
                                HttpStatus.NOT_FOUND,
                                logger
                        ));
                long activeParticipations =
                        userChallengeRepository.countSlotConsumingParticipations(userId);
                int limit = challengeParticipationProperties.getMaxActiveParticipations();
                if (limit > 0 && activeParticipations >= limit) {
                    throw new ChallengeSphereException(
                            "You can participate in at most " + limit
                                    + " active challenges at a time. Complete or leave a challenge before joining another.",
                            HttpStatus.CONFLICT,
                            logger
                    );
                }
            }

            // Determine failed users (non-existent + already joined)
            List<Long> failedUserIds = new ArrayList<>();
            failedUserIds.addAll(nonExistentUserIds);
            failedUserIds.addAll(alreadyJoinedUserIds);

            // Batch insert/update: Create new associations and reactivate previously inactive links.
            if (!successfulUserIds.isEmpty()) {
                Timestamp now = new Timestamp(System.currentTimeMillis());
                List<UserChallenge> userChallenges = new ArrayList<>();
                for (Long userId : successfulUserIds) {
                    UserChallenge userChallenge = existingByUserId.get(userId);
                    if (userChallenge == null) {
                        userChallenge = new UserChallenge();
                        userChallenge.setUserId(userId);
                        userChallenge.setChallengeId(challengeId);
                    }
                    userChallenge.setJoinedAt(now);
                    userChallenge.setActive(true);
                    userChallenge.setCompleted(false);
                    userChallenge.setCompletedAt(null);
                    userChallenges.add(userChallenge);
                }

                userChallengeRepository.saveAll(userChallenges);
                logger.info("Successfully joined {} users to challenge {}", successfulUserIds.size(), challengeId);

                // Update challengesAccepted count for all successfully joined users
                for (Long userId : successfulUserIds) {
                    updateChallengesAcceptedCountAsync(userId, true);
                }

                notifyChallengeOwnersOfJoins(challenge, successfulUserIds);
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
            logger.error("Error processing join request for challenge {}: {}", challengeId, e.getClass().getSimpleName());
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
            Long currentOwnerUserId = challenge.getCurrentOwnerId() != null
                    ? challenge.getCurrentOwnerId()
                    : challenge.getCreatorId();

            for (Long userId : userIds) {
                if (Boolean.TRUE.equals(challenge.getActive()) && userId.equals(currentOwnerUserId)) {
                    logger.info("Current owner {} cannot leave active challenge {}", userId, challengeId);
                    failedUserIds.add(userId);
                    continue;
                }
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

                // Access revocation must not depend on asynchronous broad cache invalidation.
                // Remove each leaving user's ID-only timeline so the next read rebuilds from DB;
                // rendered feed responses are intentionally no longer cached.
                for (Long userId : successfulUserIds) {
                    cacheService.invalidateTimeline(userId);
                }
                cacheService.invalidateChallengeParticipants(challengeId);

                // Update challengesAccepted count for all successfully deactivated users
                for (Long userId : successfulUserIds) {
                    updateChallengesAcceptedCountAsync(userId, false);
                }
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
            logger.error("Error processing leave request for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to leave challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public LeaveChallengeResponse removeParticipant(
            Long challengeId,
            Long actorUserId,
            Long participantUserId,
            String reason) throws ChallengeSphereException {
        logger.info("Processing creator remove participant request for challenge {} by actor {}", challengeId, actorUserId);

        try {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            if (!challenge.getCreatorId().equals(actorUserId)) {
                throw new ChallengeSphereException(
                        "Only the challenge creator can remove participants",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            if (challenge.getCreatorId().equals(participantUserId)) {
                throw new ChallengeSphereException(
                        "The challenge creator cannot be removed",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            String normalizedReason = reason == null ? "" : reason.trim();
            if (normalizedReason.length() < 10 || normalizedReason.length() > 500) {
                throw new ChallengeSphereException(
                        "A removal reason must contain between 10 and 500 characters",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            UserChallenge membership = userChallengeRepository
                    .findByUserIdAndChallengeId(participantUserId, challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "User is not a participant of this challenge",
                            HttpStatus.BAD_REQUEST,
                            logger
                    ));
            if (!Boolean.TRUE.equals(membership.getActive())) {
                throw new ChallengeSphereException(
                        "User is not an active participant of this challenge",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            membership.setActive(false);
            membership.setBanned(true);
            membership.setRemovalReason(normalizedReason);
            membership.setRemovedAt(new Timestamp(System.currentTimeMillis()));
            membership.setRemovedByUserId(actorUserId);
            userChallengeRepository.save(membership);

            try {
                cacheService.invalidateTimeline(participantUserId);
                cacheService.invalidateChallengeParticipants(challengeId);
            } catch (Exception cacheException) {
                logger.warn(
                        "Participant {} was removed from challenge {}, but cache invalidation failed: {}",
                        participantUserId,
                        challengeId,
                        cacheException.getClass().getSimpleName());
            }
            updateChallengesAcceptedCountAsync(participantUserId, false);

            return LeaveChallengeResponse.success(
                    challengeId,
                    challenge.getTitle(),
                    List.of(participantUserId)
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error removing participant {} from challenge {}: {}", participantUserId, challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to remove participant",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional
    public ChallengeParticipantsResponse getChallengeParticipants(GetChallengeParticipantsRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        Long currentUserId = request.getCurrentUserId();

        logger.info("Getting active participants for challenge {}", challengeId);

        try {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            if (!canViewChallenge(challenge, currentUserId)) {
                throw new ChallengeSphereException(
                        "Not authorized to view participants for this challenge",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            List<UserChallenge> activeUserChallenges = userChallengeRepository.findActiveByChallengeId(challengeId);

            Set<Long> userIds = new HashSet<>();
            userIds.add(challenge.getCreatorId());
            activeUserChallenges.stream()
                    .map(UserChallenge::getUserId)
                    .forEach(userIds::add);

            if (userIds.isEmpty()) {
                logger.info("No participants found for challenge: {}", challengeId);
                return ChallengeParticipantsResponse.create(challengeId, new ArrayList<>());
            }

            List<User> users = userRepository.findAllById(new ArrayList<>(userIds));

            logger.info("Found {} participants (including creator) for challenge {}", users.size(), challengeId);

            return ChallengeParticipantsResponse.create(
                    challengeId,
                    withReadableParticipantProfilePhotos(users)
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching participants for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to fetch challenge participants",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    private List<User> withReadableParticipantProfilePhotos(List<User> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.stream().map(user -> {
            User participant = new User();
            participant.setId(user.getId());
            participant.setUsername(user.getUsername());
            participant.setProfilePicUrl(
                    user.getProfilePicUrl() == null || user.getProfilePicUrl().isBlank()
                            ? user.getProfilePicUrl()
                            : mediaUrlService.toReadableProfilePhoto(user.getId(), user.getProfilePicUrl())
            );
            participant.setActive(user.getActive());
            participant.setCreatedAt(user.getCreatedAt());
            participant.setGenuinenessScore(user.getGenuinenessScore());
            participant.setChallengesCreated(user.getChallengesCreated());
            participant.setChallengesAccepted(user.getChallengesAccepted());
            participant.setFollowers(user.getFollowers());
            participant.setFollowing(user.getFollowing());
            participant.setSubmissionsCount(user.getSubmissionsCount());
            return participant;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengesByHashtagResponse getChallengesByHashtag(GetChallengesByHashtagRequest request) throws ChallengeSphereException {
        List<String> hashtags = request.getHashtags();
        Long currentUserId = request.getCurrentUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting challenges by hashtag filter for userId={}, page={}, pageSize={}",
                currentUserId, page, pageSize);

        try {
            if (currentUserId == null || !userRepository.existsById(currentUserId)) {
                return ChallengesByHashtagResponse.create(hashtags, new ArrayList<>(), page, pageSize, 0);
            }

            // Category chips are backed by this endpoint in the app, so match both hashtags and categories.
            List<Challenge> matchingChallenges = hashtags.stream()
                    .flatMap(hashtag -> java.util.stream.Stream.concat(
                            challengeRepository.findByHashtagsContaining(hashtag).stream(),
                            challengeRepository.findByCategoriesContaining(hashtag).stream()
                    ))
                    .filter(Challenge::getActive)
                    .filter(challenge -> !isExpiredChallenge(challenge))
                    .filter(challenge -> canViewChallengeDiscovery(challenge, currentUserId))
                    .distinct()
                    .sorted(ChallengeServiceImpl::compareChallengesByCreatedAtDesc)
                    .toList();

            if (matchingChallenges.isEmpty()) {
                logger.info("No active challenges found for hashtag filter");
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

            // Preserve newest-first challenge ordering while adding participant counts.
            List<ChallengesByHashtagResponse.ChallengeWithParticipantCount> challengeWithCounts =
                    withReadableCoverPhotoUrls(matchingChallenges).stream()
                    .map(challenge -> new ChallengesByHashtagResponse.ChallengeWithParticipantCount(
                            challenge,
                            participantCountMap.getOrDefault(challenge.getId(), 0L).intValue()
                    ))
                    .toList();

            // Apply pagination
            int totalCount = challengeWithCounts.size();
            int offset = (page - 1) * pageSize;
            List<ChallengesByHashtagResponse.ChallengeWithParticipantCount> paginatedChallenges = challengeWithCounts.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            logger.info("Found {} challenges for hashtag filter (showing page {}/{})",
                    totalCount, page, (int) Math.ceil((double) totalCount / pageSize));

            ChallengesByHashtagResponse response = ChallengesByHashtagResponse.create(hashtags, paginatedChallenges, page, pageSize, totalCount);

            return response;

        } catch (Exception e) {
            logger.error("Hashtag challenge lookup failed | exception={}", e.getClass().getSimpleName());
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
        Long currentUserId = request.getCurrentUserId();
        Boolean activeFilter = request.getActive();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        int page = request.getPage();
        int pageSize = request.getPageSize();

        logger.info("Getting popular challenges - currentUserId: {}, active: {}, startDate: {}, endDate: {}, page: {}, pageSize: {}",
                currentUserId, activeFilter, startDate, endDate, page, pageSize);

        try {
            if (currentUserId == null || !userRepository.existsById(currentUserId)) {
                return PopularChallengesResponse.create(new ArrayList<>(), page, pageSize, 0);
            }

            // Get all public challenges
            List<Challenge> publicChallenges = challengeRepository.findAll().stream()
                    .filter(challenge -> !challenge.getIsPrivate())
                    .filter(challenge -> !isExpiredChallenge(challenge))
                    .filter(challenge -> canViewChallengeDiscovery(challenge, currentUserId))
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

            // Add participant counts, then order by challenge creation time before pagination.
            List<PopularChallengesResponse.ChallengeWithParticipantCount> challengeWithCounts =
                    withReadableCoverPhotoUrls(publicChallenges).stream()
                    .map(challenge -> new PopularChallengesResponse.ChallengeWithParticipantCount(
                            challenge,
                            participantCountMap.getOrDefault(challenge.getId(), 0L).intValue()
                    ))
                    .sorted((left, right) -> compareCreatedAtDesc(
                            left.getCreatedAt(), left.getId(), right.getCreatedAt(), right.getId()))
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

            return response;

        } catch (Exception e) {
            logger.error("Error fetching popular challenges: {}", e.getClass().getSimpleName());
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
    public RecentChallengesResponse getRecentChallenges(int page, int pageSize, Long currentUserId) throws ChallengeSphereException {
        logger.info("Getting recent challenges - currentUserId: {}, page: {}, pageSize: {}",
                currentUserId, page, pageSize);

        try {
            if (currentUserId == null || !userRepository.existsById(currentUserId)) {
                return RecentChallengesResponse.empty(page, pageSize);
            }

            // Validate pagination parameters
            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 10;
            if (pageSize > 100) pageSize = 100; // Cap at 100

            List<Challenge> visibleChallenges = challengeRepository.findAllPublicChallenges().stream()
                    .sorted((left, right) -> {
                        if (left.getCreatedAt() == null && right.getCreatedAt() == null) return 0;
                        if (left.getCreatedAt() == null) return 1;
                        if (right.getCreatedAt() == null) return -1;
                        return right.getCreatedAt().compareTo(left.getCreatedAt());
                    })
                    .filter(challenge -> !isExpiredChallenge(challenge))
                    .filter(challenge -> canViewChallengeDiscovery(challenge, currentUserId))
                    .toList();

            if (visibleChallenges.isEmpty()) {
                return RecentChallengesResponse.empty(page, pageSize);
            }

            int totalCount = visibleChallenges.size();
            int offset = (page - 1) * pageSize;
            List<Challenge> challenges = visibleChallenges.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

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

            // Convert to response with engagement metrics
            final Map<Long, Long> finalParticipantMap = participantCountMap;
            List<RecentChallengesResponse.ChallengeInfo> challengeInfos =
                    withReadableCoverPhotoUrls(challenges).stream()
                    .map(challenge -> {
                        int participantCount = finalParticipantMap.getOrDefault(challenge.getId(), 0L).intValue();
                        return RecentChallengesResponse.ChallengeInfo.fromChallenge(
                                challenge, 0, 0, participantCount);
                    })
                    .toList();

            RecentChallengesResponse response = RecentChallengesResponse.create(
                    challengeInfos, page, pageSize, (int) totalCount);

            return response;

        } catch (Exception e) {
            logger.error("Error fetching recent challenges: {}", e.getClass().getSimpleName());
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

        return List.of();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Challenge updateChallenge(Long id, ChallengeUpdateRequest request, Long actorUserId, boolean admin) throws ChallengeSphereException {
        logger.info("Updating challenge id: {}", id);

        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + id,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        boolean owner = actorUserId != null && (actorUserId.equals(challenge.getCreatorId()) || actorUserId.equals(challenge.getCurrentOwnerId()));
        if (!admin && !owner) {
            throw new ChallengeSphereException("Not authorized to update this challenge", HttpStatus.FORBIDDEN, logger);
        }

        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            challenge.setTitle(request.getTitle());
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            challenge.setDescription(request.getDescription());
        }
        if (request.getHashtags() != null) {
            challenge.setHashtags(request.getHashtags());
        }
        if (request.getCategories() != null) {
            challenge.setCategories(request.getCategories());
        }
        if (request.getBenefits() != null) {
            challenge.setBenefits(request.getBenefits());
        }
        if (request.getIsPrivate() != null) {
            challenge.setIsPrivate(request.getIsPrivate());
        }
        if (request.getChallengeMode() != null) {
            challenge.setChallengeMode(normalizeChallengeMode(request.getChallengeMode()));
        }
        if (request.getNumberOfDays() != null) {
            challenge.setNumberOfDays(resolveChallengeDuration(request.getNumberOfDays(), challenge.getChallengeMode()));
        } else if (Challenge.isOneOffMode(challenge.getChallengeMode())) {
            challenge.setNumberOfDays(1);
        }
        if (request.getActive() != null
                && !admin
                && !java.util.Objects.equals(request.getActive(), challenge.getActive())) {
            throw new ChallengeSphereException(
                    "Only an administrator can change a challenge's active status",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (request.getActive() != null && admin) {
            challenge.setActive(request.getActive());
        }
        List<String> requestedCoverPhotoUrls = getRequestedCoverPhotoUrls(request);
        if (requestedCoverPhotoUrls != null) {
            Long mediaOwnerId = actorUserId != null
                    ? actorUserId
                    : (challenge.getCurrentOwnerId() != null
                            ? challenge.getCurrentOwnerId()
                            : challenge.getCreatorId());
            challenge.setCoverPhotoUrls(validateAndSerializeCoverPhotoUrls(
                    mediaOwnerId, requestedCoverPhotoUrls, challenge.getCoverPhotoUrls()));
        }

        if (actorUserId != null
                && actorUserId.equals(challenge.getCreatorId())
                && request.getInvitedUserIds() != null
                && !request.getInvitedUserIds().isEmpty()) {
            Set<Long> invitedUserIds = request.getInvitedUserIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(userId -> userId > 0)
                    .collect(Collectors.toSet());
            List<UserChallenge> reinstatedMemberships = userChallengeRepository.findByChallengeId(id).stream()
                    .filter(membership -> invitedUserIds.contains(membership.getUserId()))
                    .filter(membership -> !Boolean.TRUE.equals(membership.getActive()))
                    .filter(membership -> Boolean.TRUE.equals(membership.getBanned()))
                    .peek(membership -> {
                        membership.setBanned(false);
                        membership.setRemovalReason(null);
                        membership.setRemovedAt(null);
                        membership.setRemovedByUserId(null);
                    })
                    .toList();
            if (!reinstatedMemberships.isEmpty()) {
                userChallengeRepository.saveAll(reinstatedMemberships);
            }
        }

        Challenge updated = challengeRepository.save(challenge);
        cacheService.invalidateChallenge(id);
        cacheService.invalidateAllFeeds();
        notificationService.notifyChallengeInvitesAsync(
                updated,
                actorUserId,
                request.getInvitedUserIds()
        );
        logger.info("Challenge {} updated successfully", id);
        return withReadableCoverPhotoUrls(updated);
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

            if (!canViewChallenge(challenge, currentUserId)) {
                throw new ChallengeSphereException(
                        "Not authorized to view this challenge",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            int participantCount = userChallengeRepository.countActiveByChallengeId(challengeId);

            boolean isJoined = false;
            if (currentUserId != null) {
                isJoined = userChallengeRepository.findByUserIdAndChallengeId(currentUserId, challengeId)
                        .map(uc -> Boolean.TRUE.equals(uc.getActive()))
                        .orElse(false);
            }

            return ChallengeDetailResponse.from(withReadableCoverPhotoUrls(challenge), participantCount, isJoined);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching challenge detail for id {}: {}", challengeId, e.getClass().getSimpleName());
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

            if (!canViewChallenge(challenge, userId)) {
                throw new ChallengeSphereException(
                        "Not authorized to view this challenge",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            List<com.ironside.challengesphere.model.Submission> submissions =
                    submissionRepository.findActiveByUserIdAndChallengeId(userId, challengeId);

            int totalDays = requireValidChallengeDuration(challenge.getNumberOfDays());
            Map<Integer, Submission> byDay = submissions.stream()
                    .filter(submission -> isValidChallengeDay(submission.getDayNumber(), totalDays))
                    .collect(Collectors.toMap(
                            Submission::getDayNumber,
                            s -> s,
                            (a, b) -> a // keep first if duplicate day
                    ));

            List<ChallengeProgressResponse.DayProgress> days = new ArrayList<>();
            for (int d = 1; d <= totalDays; d++) {
                Submission s = byDay.get(d);
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
            logger.error("Error fetching progress: challengeId={}, userId={}: {}", challengeId, userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to fetch challenge progress", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MyChallengesResponse getMyChallenges(Long userId, Long currentUserId, String status, int page, int pageSize) throws ChallengeSphereException {
        logger.info("Fetching my challenges: userId={}, viewerId={}, status={}, page={}, pageSize={}",
                userId, currentUserId, status, page, pageSize);
        try {
            ensureFollowAccess(userId, currentUserId, "challenges");

            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 10;
            if (pageSize > 100) pageSize = 100;

            List<UserChallenge> userChallenges = userChallengeRepository.findActiveByUserId(userId);

            List<Long> challengeIds = userChallenges.stream().map(UserChallenge::getChallengeId).toList();
            Map<Long, Long> activeParticipantCountMap = challengeIds.isEmpty()
                    ? Map.of()
                    : userChallengeRepository.findActiveByChallengeIds(challengeIds).stream()
                    .collect(Collectors.groupingBy(UserChallenge::getChallengeId, Collectors.counting()));
            List<Challenge> visibleChallenges = challengeRepository.findAllById(challengeIds);
            if (!java.util.Objects.equals(userId, currentUserId)) {
                visibleChallenges = visibleChallenges.stream()
                        .filter(challenge -> canViewChallenge(challenge, currentUserId))
                        .toList();
            }
            Map<Long, Challenge> challengeMap = withReadableCoverPhotoUrls(visibleChallenges)
                    .stream().collect(Collectors.toMap(Challenge::getId, c -> c));

            List<MyChallengesResponse.ChallengeSummary> summaries = new ArrayList<>();
            for (UserChallenge uc : userChallenges) {
                Challenge c = challengeMap.get(uc.getChallengeId());
                if (c == null) continue;

                List<com.ironside.challengesphere.model.Submission> subs =
                        submissionRepository.findActiveByUserIdAndChallengeId(userId, uc.getChallengeId());

                boolean oneOff = Challenge.isOneOffMode(c.getChallengeMode());
                int totalDays = oneOff ? 1 : c.getNumberOfDays();
                List<Integer> submittedDays = distinctSubmittedDays(subs, totalDays);
                int daysCompleted = oneOff && !submittedDays.isEmpty() ? 1 : submittedDays.size();
                int completionPct = totalDays > 0 ? (int) Math.round((daysCompleted * 100.0) / totalDays) : 0;

                MyChallengesResponse.ChallengeSummary summary = MyChallengesResponse.ChallengeSummary.from(
                        c
                );
                summary.setDaysCompleted(daysCompleted);
                summary.setCurrentStreak(computeCurrentStreak(submittedDays));
                summary.setCompletionPercentage(completionPct);
                summary.setJoinedAt(uc.getJoinedAt());
                summary.setCompleted(oneOff
                        ? daysCompleted == 1
                        : Boolean.TRUE.equals(uc.getCompleted()) || isExpiredChallenge(c));
                int participantCount = activeParticipantCountMap.getOrDefault(c.getId(), 0L).intValue();
                summary.setParticipantCount(participantCount);
                summary.setActiveParticipantCount(participantCount);
                summaries.add(summary);
            }

            if (status != null && !status.isBlank()) {
                boolean filterCompleted = "completed".equalsIgnoreCase(status);
                summaries = summaries.stream()
                        .filter(summary -> summary.isCompleted() == filterCompleted)
                        .toList();
            }

            summaries = summaries.stream()
                    .sorted((left, right) -> compareCreatedAtDesc(
                            left.getCreatedAt(), left.getId(), right.getCreatedAt(), right.getId()))
                    .toList();

            int totalCount = summaries.size();
            int offset = (page - 1) * pageSize;
            List<MyChallengesResponse.ChallengeSummary> paginatedSummaries = summaries.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            return MyChallengesResponse.create(paginatedSummaries, page, pageSize, totalCount);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching my challenges for userId={}: {}", userId, e.getClass().getSimpleName());
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
            if (request.getMediaFiles().size() > MAX_CHALLENGE_COVER_PHOTOS) {
                throw new ChallengeSphereException("A challenge can have at most 5 cover photos", HttpStatus.BAD_REQUEST, logger);
            }

            uploadQuotaService.reserve(request.getUserId(), request.getMediaFiles());

            long expiry = 3600L;
            List<PresignedUrlsResponse.PresignedUrlInfo> urls = new ArrayList<>();
            for (GeneratePresignedUrlsRequest.MediaFileInfo file : request.getMediaFiles()) {
                String key = challengeMediaKeyPolicy.createKey(
                        request.getUserId(),
                        file.getMediaType(),
                        file.getContentType(),
                        System.currentTimeMillis(),
                        UUID.randomUUID());
                issuedUploadService.issue(
                        request.getUserId(), key, IssuedUploadService.Purpose.CHALLENGE_COVER);
                urls.add(new PresignedUrlsResponse.PresignedUrlInfo(
                        file.getFileName(),
                        file.getMediaType().name(),
                        file.getContentType().getMimeType(),
                        GetSignedUrlForS3.generatePutObjectSignedUrl(
                                bucketName,
                                key, expiry, file.getContentType(), file.getFileSizeBytes()),
                        key, expiry));
            }
            return PresignedUrlsResponse.success(request.getUserId(), urls);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error generating challenge presigned URLs for userId={}: {}", request.getUserId(), e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to generate presigned URLs", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }
    }

    private String firstUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        return parts.length > 0 ? parts[0].trim() : null;
    }

    private String normalizeChallengeMode(String challengeMode) throws ChallengeSphereException {
        if (challengeMode == null || challengeMode.isBlank()) {
            return Challenge.MODE_DAILY;
        }

        String normalized = challengeMode.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if ("DAILY".equals(normalized)) {
            return Challenge.MODE_DAILY;
        }
        if ("ONE_OFF".equals(normalized) || "ONE_TIME".equals(normalized) || "ADHOC".equals(normalized)) {
            return Challenge.MODE_ONE_OFF;
        }

        throw new ChallengeSphereException(
                "Challenge mode must be DAILY or ONE_OFF",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }

    private int resolveChallengeDuration(Integer requestedDays, String challengeMode)
            throws ChallengeSphereException {
        if (Challenge.isOneOffMode(challengeMode)) {
            return 1;
        }
        return requireValidChallengeDuration(requestedDays != null ? requestedDays : 1);
    }

    private int requireValidChallengeDuration(Integer numberOfDays)
            throws ChallengeSphereException {
        if (numberOfDays == null || numberOfDays < 1 || numberOfDays > MAX_CHALLENGE_DURATION_DAYS) {
            throw new ChallengeSphereException(
                    "Challenge duration must be between 1 and " + MAX_CHALLENGE_DURATION_DAYS + " days",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return numberOfDays;
    }

    private boolean isExpiredChallenge(Challenge challenge) {
        if (challenge == null || Challenge.isOneOffMode(challenge.getChallengeMode())) {
            return false;
        }
        LocalDate startDate = challenge.getStartDate();
        Integer numberOfDays = challenge.getNumberOfDays();
        if (startDate == null || numberOfDays == null || numberOfDays <= 0) {
            return false;
        }
        return !LocalDate.now().isBefore(startDate.plusDays(numberOfDays));
    }

    private List<Integer> distinctSubmittedDays(List<Submission> submissions, int totalDays) {
        if (submissions == null || submissions.isEmpty()) {
            return List.of();
        }
        return submissions.stream()
                .map(Submission::getDayNumber)
                .filter(dayNumber -> isValidChallengeDay(dayNumber, totalDays))
                .distinct()
                .sorted()
                .toList();
    }

    private boolean isValidChallengeDay(Integer dayNumber, int totalDays) {
        return dayNumber != null && dayNumber >= 1 && (totalDays <= 0 || dayNumber <= totalDays);
    }

    private List<Challenge> withReadableCoverPhotoUrls(List<Challenge> challenges) {
        if (challenges == null || challenges.isEmpty()) {
            return List.of();
        }

        Set<Long> creatorIds = challenges.stream()
                .map(Challenge::getCreatorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> creatorsById = userRepository.findAllById(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, creator -> creator));

        return challenges.stream()
                .map(challenge -> withReadableChallengeMedia(
                        challenge,
                        creatorsById.get(challenge.getCreatorId())
                ))
                .toList();
    }

    private Challenge withReadableCoverPhotoUrls(Challenge challenge) {
        if (challenge == null) {
            return null;
        }

        User creator = challenge.getCreatorId() == null
                ? null
                : userRepository.findById(challenge.getCreatorId()).orElse(null);
        return withReadableChallengeMedia(challenge, creator);
    }

    private Challenge withReadableChallengeMedia(Challenge challenge, User creator) {
        Challenge copy = new Challenge();
        copy.setId(challenge.getId());
        copy.setHashtags(challenge.getHashtags());
        copy.setCategories(challenge.getCategories());
        copy.setTitle(challenge.getTitle());
        copy.setDescription(challenge.getDescription());
        copy.setBenefits(challenge.getBenefits());
        copy.setIsPrivate(challenge.getIsPrivate());
        copy.setStartDate(challenge.getStartDate());
        copy.setActive(challenge.getActive());
        copy.setCreatedAt(challenge.getCreatedAt());
        copy.setCreatorId(challenge.getCreatorId());
        copy.setCreatorUsername(
                creator == null ? challenge.getCreatorUsername() : creator.getUsername()
        );
        copy.setCreatorProfilePicUrl(toReadableProfilePicUrl(
                creator == null ? challenge.getCreatorId() : creator.getId(),
                creator == null ? challenge.getCreatorProfilePicUrl() : creator.getProfilePicUrl()
        ));
        copy.setCurrentOwnerId(challenge.getCurrentOwnerId());
        copy.setNumberOfDays(challenge.getNumberOfDays());
        copy.setChallengeMode(challenge.getChallengeMode());
        copy.setCoverPhotoUrls(toReadableCoverPhotoUrls(challenge));
        return copy;
    }

    private String toReadableProfilePicUrl(Long userId, String profilePicUrl) {
        if (profilePicUrl == null || profilePicUrl.isBlank() || mediaUrlService == null) {
            return profilePicUrl;
        }

        try {
            return mediaUrlService.toReadableProfilePhoto(userId, profilePicUrl);
        } catch (Exception e) {
            logger.warn("Failed to prepare readable creator profile photo URL: {}", e.getClass().getSimpleName());
            return profilePicUrl;
        }
    }

    private String toReadableCoverPhotoUrls(Challenge challenge) {
        String rawCoverPhotoUrls = challenge == null ? null : challenge.getCoverPhotoUrls();
        if (rawCoverPhotoUrls == null || rawCoverPhotoUrls.isBlank()) {
            return rawCoverPhotoUrls;
        }
        if (mediaUrlService == null) {
            return null;
        }

        try {
            Set<Long> allowedOwnerIds = new HashSet<>();
            if (challenge.getCreatorId() != null) {
                allowedOwnerIds.add(challenge.getCreatorId());
            }
            if (challenge.getCurrentOwnerId() != null) {
                allowedOwnerIds.add(challenge.getCurrentOwnerId());
            }
            List<String> safeKeys = challengeMediaKeyPolicy.filterOwnedKeysForRead(
                    rawCoverPhotoUrls, allowedOwnerIds);
            return safeKeys.isEmpty()
                    ? null
                    : mediaUrlService.toReadableMediaUrls(String.join(",", safeKeys));
        } catch (Exception e) {
            logger.warn("Failed to prepare readable challenge cover URL(s): {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private List<String> getRequestedCoverPhotoUrls(ChallengeCreateRequest request) {
        if (request.getCoverPhotoUrls() != null) {
            return request.getCoverPhotoUrls();
        }
        if (request.getMediaUrls() != null) {
            return request.getMediaUrls();
        }
        if (request.getMediaUrl() != null) {
            return List.of(request.getMediaUrl());
        }
        return null;
    }

    private List<String> getRequestedCoverPhotoUrls(ChallengeUpdateRequest request) {
        if (request.getCoverPhotoUrls() != null) {
            return request.getCoverPhotoUrls();
        }
        if (request.getMediaUrls() != null) {
            return request.getMediaUrls();
        }
        if (request.getMediaUrl() != null) {
            return List.of(request.getMediaUrl());
        }
        return null;
    }

    private String validateAndSerializeCoverPhotoUrls(
            Long ownerUserId,
            List<String> urls,
            String existingMediaKeys)
            throws ChallengeSphereException {
        if (existingMediaKeys != null
                && urls != null
                && urls.size() <= MAX_CHALLENGE_COVER_PHOTOS
                && urls.stream().allMatch(java.util.Objects::nonNull)) {
            String unchangedCandidate = urls.stream()
                    .map(String::trim)
                    .collect(Collectors.joining(","));
            if (unchangedCandidate.equals(existingMediaKeys)) {
                return existingMediaKeys;
            }
        }

        if (urls == null || urls.size() > MAX_CHALLENGE_COVER_PHOTOS) {
            throw new ChallengeSphereException(
                    "Invalid challenge cover media reference",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        Set<String> existingKeys = existingMediaKeys == null || existingMediaKeys.isBlank()
                ? Set.of()
                : new HashSet<>(List.of(existingMediaKeys.split(",")));
        List<String> requestedKeys = urls.stream()
                .map(value -> value == null ? null : value.trim())
                .toList();
        List<String> newKeyCandidates = requestedKeys.stream()
                .filter(java.util.Objects::nonNull)
                .filter(key -> !existingKeys.contains(key))
                .toList();
        List<String> validatedNewKeys = challengeMediaKeyPolicy.validateAndNormalize(
                ownerUserId, newKeyCandidates);
        Set<String> validatedNewKeySet = new HashSet<>(validatedNewKeys);
        List<String> mediaKeys = new ArrayList<>(requestedKeys.size());
        Set<String> seenKeys = new HashSet<>();
        for (String key : requestedKeys) {
            if (key == null
                    || (!existingKeys.contains(key) && !validatedNewKeySet.contains(key))
                    || !seenKeys.add(key)) {
                throw new ChallengeSphereException(
                        "Invalid challenge cover media reference",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            mediaKeys.add(key);
        }
        if (mediaKeys.isEmpty()) {
            return null;
        }

        String serializedMediaKeys = String.join(",", mediaKeys);
        if (serializedMediaKeys.equals(existingMediaKeys)) {
            return serializedMediaKeys;
        }

        issuedUploadService.consume(
                ownerUserId, validatedNewKeys, IssuedUploadService.Purpose.CHALLENGE_COVER);

        ContentModerationService.Result moderationResult;
        try {
            moderationResult = validatedNewKeys.isEmpty()
                    ? new ContentModerationService.Result(
                            ContentModerationService.Verdict.CLEAN, List.of())
                    : contentModerationService.scanStrict(validatedNewKeys);
        } catch (Exception e) {
            logger.error("Challenge cover moderation failed closed for userId={}: {}",
                    ownerUserId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Challenge cover photos could not be verified",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger
            );
        }
        if (moderationResult.verdict() != ContentModerationService.Verdict.CLEAN) {
            throw new ChallengeSphereException(
                    "One or more challenge cover photos violate the media policy",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return serializedMediaKeys;
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

    private static int compareChallengesByCreatedAtDesc(Challenge left, Challenge right) {
        return compareCreatedAtDesc(
                left == null ? null : left.getCreatedAt(),
                left == null ? null : left.getId(),
                right == null ? null : right.getCreatedAt(),
                right == null ? null : right.getId()
        );
    }

    private static int compareCreatedAtDesc(
            Timestamp leftCreatedAt,
            Long leftId,
            Timestamp rightCreatedAt,
            Long rightId
    ) {
        if (leftCreatedAt != null && rightCreatedAt != null) {
            int createdAtComparison = rightCreatedAt.compareTo(leftCreatedAt);
            if (createdAtComparison != 0) {
                return createdAtComparison;
            }
        } else if (leftCreatedAt == null && rightCreatedAt != null) {
            return 1;
        } else if (leftCreatedAt != null) {
            return -1;
        }

        long safeLeftId = leftId == null ? 0L : leftId;
        long safeRightId = rightId == null ? 0L : rightId;
        return Long.compare(safeRightId, safeLeftId);
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

    private void ensureFollowAccess(Long ownerUserId, Long viewerUserId, String contentType)
            throws ChallengeSphereException {
        if (ownerUserId == null) {
            return;
        }
        if (viewerUserId == null) {
            throw new ChallengeSphereException(
                    "currentUserId is required to view this " + contentType,
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (ownerUserId.equals(viewerUserId)) {
            return;
        }
        if (!followRepository.existsActiveMutualFollow(viewerUserId, ownerUserId)) {
            throw new ChallengeSphereException(
                    "You can only view this user's " + contentType + " if you follow each other",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
    }

    private boolean canViewChallengeDiscovery(Challenge challenge, Long currentUserId) {
        return canViewChallenge(challenge, currentUserId);
    }

    private boolean canViewChallenge(Challenge challenge, Long currentUserId) {
        if (challenge == null || currentUserId == null || !Boolean.TRUE.equals(challenge.getActive())) {
            return false;
        }
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            return true;
        }
        Long ownerUserId = challenge.getCurrentOwnerId() != null ? challenge.getCurrentOwnerId() : challenge.getCreatorId();
        if (currentUserId.equals(ownerUserId) || currentUserId.equals(challenge.getCreatorId())) {
            return true;
        }
        return userChallengeRepository.findByUserIdAndChallengeId(currentUserId, challenge.getId())
                .map(UserChallenge::getActive)
                .orElse(false);
    }

    private boolean canJoinChallenge(
            Challenge challenge,
            Long ownerUserId,
            Long userId,
            boolean verifiedInvitation) {
        if (challenge == null || userId == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            return true;
        }
        if (userId.equals(ownerUserId) || userId.equals(challenge.getCreatorId())) {
            return true;
        }
        return verifiedInvitation;
    }

    private void notifyChallengeOwnersOfJoins(Challenge challenge, List<Long> successfulUserIds) {
        Set<Long> recipientUserIds = new HashSet<>();
        if (challenge.getCreatorId() != null) {
            recipientUserIds.add(challenge.getCreatorId());
        }
        if (challenge.getCurrentOwnerId() != null) {
            recipientUserIds.add(challenge.getCurrentOwnerId());
        }
        if (recipientUserIds.isEmpty()) {
            return;
        }

        for (Long joinedUserId : successfulUserIds) {
            try {
                notificationService.notifyChallengeJoined(challenge, joinedUserId, recipientUserIds);
            } catch (Exception e) {
                logger.warn("Failed to notify challenge owner(s) for challengeId={} joinedUserId={}: {}",
                        challenge.getId(), joinedUserId, e.getClass().getSimpleName());
            }
        }
    }
}
