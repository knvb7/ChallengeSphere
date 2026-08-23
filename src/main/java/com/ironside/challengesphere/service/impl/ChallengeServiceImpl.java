package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import com.ironside.challengesphere.api.response.ChallengeFavoriteResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengeProgressResponse;
import com.ironside.challengesphere.api.response.ChallengeMomentumResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
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
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ParticipationQuotaStore.ParticipationQuotaExceededException;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.ChallengeInviteRepository;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.IssuedUploadService;
import com.ironside.challengesphere.security.UploadQuotaService;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.util.GetSignedUrlForS3;
import com.ironside.challengesphere.util.StreakCalculator;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class ChallengeServiceImpl implements ChallengeService {
    private static final Logger logger = LogManager.getLogger(ChallengeServiceImpl.class);
    private static final int MAX_CHALLENGE_COVER_PHOTOS = 5;
    private static final int MAX_CHALLENGE_DURATION_DAYS = 365;
    private static final int MAX_DELETE_MEMBER_COUNT = 3;
    private static final int MAX_TAG_FILTER_VALUES = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_FILTER_PAGE_READS = 10;
    private static final int MAX_FILTER_CANDIDATES = 500;
    private static final String CURSOR_DONE = "__done";
    private static final String USER_STREAM_CREATED = "created";
    private static final String USER_STREAM_OWNED = "owned";
    private static final String USER_STREAM_JOINED = "joined";
    private static final long MAX_DELETE_AGE_MILLIS = Duration.ofDays(3).toMillis();

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private ChallengeInviteRepository challengeInviteRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private ChallengeCrewLifecycleRepository challengeCrewLifecycleRepository;

    @Autowired
    private ChallengeParticipationProperties challengeParticipationProperties;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private ChallengeMomentumPolicy challengeMomentumPolicy;

    @Autowired
    private LeaderboardService leaderboardService;

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
    private ModerationEnforcementService moderationEnforcementService;

    @Autowired
    private UploadQuotaService uploadQuotaService;

    @Autowired
    private IssuedUploadService issuedUploadService;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Override
    public Optional<Challenge> getChallengeById(String challengeId) throws ChallengeSphereException {
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
        String userId = request.getUserId();
        String currentUserId = request.getCurrentUserId();
        int limit = normalizeLimit(request.getLimit(), 10);
        Boolean activeFilter = request.getActive();
        String queryBinding = cursorBinding(
                "challenge:user:v2",
                userId,
                currentUserId,
                activeFilter,
                limit
        );

        logger.info("Getting challenges for userId: {}, limit: {}, activeFilter: {}",
                userId, limit, activeFilter);

        try {
            ensureFollowAccess(userId, currentUserId, "challenges");
            boolean selfView = userId != null && userId.equals(currentUserId);
            boolean requestedActive = activeFilter == null || activeFilter;
            Map<String, Map<String, AttributeValue>> continuation = decodeUserCollectionContinuation(
                    request.getCursor(), queryBinding, userId
            );
            if (!selfView && !requestedActive) {
                return UserChallengesResponse.create(userId, List.of(), null, false);
            }
            List<Challenge> challenges = new ArrayList<>(limit);
            int candidatesExamined = 0;
            int pageReads = 0;
            boolean hasMore = true;
            search:
            while (challenges.size() < limit
                    && candidatesExamined < MAX_FILTER_CANDIDATES
                    && pageReads++ < MAX_FILTER_PAGE_READS
                    && hasMore) {
                UserChallengeCollectionPage page = findUserChallengeCollectionPage(
                        userId,
                        candidateWindow(
                                limit - challenges.size(),
                                MAX_FILTER_CANDIDATES - candidatesExamined
                        ),
                        continuation
                );
                List<Challenge> candidates = page.candidates().stream()
                        .map(UserChallengeCollectionCandidate::challenge)
                        .toList();
                Set<String> visibleIds = selfView
                        ? candidates.stream()
                                .map(Challenge::getId)
                                .collect(Collectors.toUnmodifiableSet())
                        : visibleActiveChallengeIds(candidates, currentUserId);
                int previouslyExamined = 0;
                for (UserChallengeCollectionCandidate candidate : page.candidates()) {
                    candidatesExamined += candidate.rowsExaminedThrough() - previouslyExamined;
                    previouslyExamined = candidate.rowsExaminedThrough();
                    Challenge challenge = candidate.challenge();
                    continuation = candidate.continuation();
                    hasMore = candidate.hasMore();
                    if (java.util.Objects.equals(challenge.getActive(), requestedActive)
                            && (selfView || visibleIds.contains(challenge.getId()))) {
                        challenges.add(challenge);
                        if (challenges.size() == limit) {
                            break search;
                        }
                    }
                    if (candidatesExamined == MAX_FILTER_CANDIDATES) {
                        break search;
                    }
                }
                if (page.candidates().isEmpty()) {
                    candidatesExamined += page.rowsExamined();
                    continuation = page.continuation();
                    hasMore = page.hasMore();
                } else if (previouslyExamined < page.rowsExamined()) {
                    candidatesExamined += page.rowsExamined() - previouslyExamined;
                    continuation = page.continuation();
                    hasMore = page.hasMore();
                }
            }

            logger.info("Found {} challenges for userId: {}", challenges.size(), userId);

            UserChallengesResponse response = UserChallengesResponse.create(
                    userId,
                    withReadableCoverPhotoUrls(challenges),
                    encodeShardContinuation(queryBinding, continuation, hasMore),
                    hasMore
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
    public UserChallengesResponse getPublicChallengesByCreator(
            String creatorId,
            String currentUserId,
            int limit,
            String cursor
    )
            throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 10);
        String queryBinding = cursorBinding(
                "challenge:creator-public:v2",
                creatorId,
                currentUserId,
                boundedLimit
        );
        logger.info("Getting public challenges for creatorId: {}, limit: {}, viewer: {}",
                creatorId, boundedLimit, currentUserId);

        try {
            Map<String, AttributeValue> continuation = decodeContinuation(cursor, queryBinding);
            List<Challenge> publicChallenges = new ArrayList<>(boundedLimit);
            int candidatesExamined = 0;
            int pageReads = 0;
            boolean hasMore = true;
            search:
            while (publicChallenges.size() < boundedLimit
                    && candidatesExamined < MAX_FILTER_CANDIDATES
                    && pageReads++ < MAX_FILTER_PAGE_READS
                    && hasMore) {
                int pageLimit = candidateWindow(
                        boundedLimit - publicChallenges.size(),
                        MAX_FILTER_CANDIDATES - candidatesExamined
                );
                DynamoPage<Challenge> page = challengeRepository.findPublicActiveByCreatorIdPage(
                        creatorId,
                        pageLimit,
                        continuation
                );
                candidatesExamined += page.items().size();
                Set<String> visibleIds = visibleActiveChallengeIds(page.items(), currentUserId);
                for (int index = 0; index < page.items().size(); index++) {
                    Challenge challenge = page.items().get(index);
                    continuation = continuationKey(
                            ChallengeRepositoryKeys.join(
                                    "CHALLENGE", "CREATOR", creatorId, "PUBLIC_ACTIVE"
                            ),
                            ChallengeRepositoryKeys.createdSk(
                                    challenge.getCreatedAt(), challenge.getId()
                            )
                    );
                    if (visibleIds.contains(challenge.getId())) {
                        publicChallenges.add(challenge);
                        if (publicChallenges.size() == boundedLimit) {
                            hasMore = index + 1 < page.items().size()
                                    || !page.lastEvaluatedKey().isEmpty();
                            break search;
                        }
                    }
                }
                continuation = page.lastEvaluatedKey();
                hasMore = !continuation.isEmpty();
                if (page.items().isEmpty()) {
                    break;
                }
            }

            return UserChallengesResponse.create(
                    creatorId,
                    withReadableCoverPhotoUrls(publicChallenges),
                    encodeContinuation(queryBinding, hasMore ? continuation : Map.of()),
                    hasMore
            );
        } catch (ChallengeSphereException e) {
            throw e;
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

            // Serialize quota checks for this user so concurrent create/join
            // requests cannot race past the visibility-specific active cap.
            User creator = userRepository.findByIdForUpdate(request.getCreatorUserId())
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Creator user not found with id: " + request.getCreatorUserId(),
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            enforceActiveChallengeParticipationLimit(
                    creator,
                    Boolean.TRUE.equals(request.getIsPrivate())
            );

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
            challenge.setProofMode(normalizeProofMode(request.getProofMode()));
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
            try {
                userChallengeRepository.save(creatorParticipant);
            } catch (ParticipationQuotaExceededException quotaExceeded) {
                // DynamoDB cannot transact across two independently assembled
                // aggregate writes. Compensate the new participant-less
                // challenge if the membership/slot transaction loses a race.
                try {
                    challengeRepository.delete(savedChallenge);
                } catch (RuntimeException cleanupFailure) {
                    logger.error(
                            "Failed to compensate challenge {} after participation quota rejection",
                            savedChallenge.getId()
                    );
                }
                throw quotaExceeded;
            }

            logger.info("Successfully created challenge with id: {} for user: {}", savedChallenge.getId(), request.getCreatorUserId());
            cacheService.cacheChallenge(savedChallenge.getId(), savedChallenge);
            cacheService.invalidateRecentAndTrendingChallenges();
            if (savedChallenge.getHashtags() != null && !savedChallenge.getHashtags().isBlank()) {
                cacheService.invalidateHashtagChallenges();
            }
            // Update user's challengesCreated count asynchronously
            updateChallengesCreatedCountAsync(request.getCreatorUserId());
            notifyChallengeInvitesAfterCommit(
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
                    savedChallenge.getProofMode(),
                    toReadableCoverPhotoUrls(
                            savedChallenge,
                            Map.of(creator.getId(), creator)
                    )
            );

        } catch (ParticipationQuotaExceededException e) {
            throw participationQuotaConflict(e);
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

    private void enforceActiveChallengeParticipationLimit(User user, boolean privateChallenge)
            throws ChallengeSphereException {
        boolean premiumUser = user.getPremiumUser() != null && user.getPremiumUser() > 0;
        int limit = challengeParticipationProperties.limitFor(premiumUser);

        long activeParticipations =
                userChallengeRepository.countSlotConsumingParticipationsByVisibility(
                        user.getId(),
                        privateChallenge
                );
        if (activeParticipations >= limit) {
            String visibility = privateChallenge ? "private" : "public";
            String tier = premiumUser ? "Premium" : "Free";
            throw new ChallengeSphereException(
                    tier + " users can be part of up to " + limit + " active " + visibility
                            + " challenges at a time. Complete or leave an active " + visibility
                            + " challenge before adding another.",
                    HttpStatus.CONFLICT,
                    logger
            );
        }
    }

    /**
     * Asynchronously update the challengesCreated count for a user
     */
    @Async
    @Transactional
    protected void updateChallengesCreatedCountAsync(String userId) {
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
    protected void updateChallengesAcceptedCountAsync(String userId, boolean increment) {
        try {
            logger.info("Async update challengesAccepted count for user: {}, increment: {}", userId, increment);
            userRepository.adjustChallengeAcceptedCountForAUser(userId, increment ? 1 : -1);
            cacheService.invalidateUserProfile(userId);
            logger.info("Updated challengesAccepted count for userId: {}", userId);
        } catch (Exception e) {
            logger.error("Error updating challengesAccepted count for user {}: {}", userId, e.getClass().getSimpleName());
        }
    }

    @Override
    public List<Challenge> getChallengeByIds(List<String> challengeIds, String currentUserId) throws ChallengeSphereException {
        try {
            logger.info("Fetching challenges by ids: {}", challengeIds);

            List<Challenge> result = new ArrayList<>();
            List<String> cacheMisses = new ArrayList<>();

            // Try to get challenges from cache first
            // for (String challengeId : challengeIds) {
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
            Map<String, UserChallenge> viewerMembershipByChallengeId = currentUserId == null || cacheMisses.isEmpty()
                    ? Map.of()
                    : userChallengeRepository.findByUserIdAndChallengeIds(currentUserId, cacheMisses).stream()
                            .collect(Collectors.toMap(
                                    UserChallenge::getChallengeId,
                                    membership -> membership,
                                    (left, right) -> left
                            ));
            result.addAll(dbChallenges.stream()
                    .filter(challenge -> !isBlockedChallengeOwner(challenge, currentUserId))
                    .filter(challenge -> canViewChallenge(challenge, currentUserId)
                            || canViewArchivedChallenge(
                                    challenge,
                                    viewerMembershipByChallengeId.get(challenge.getId()),
                                    currentUserId
                            ))
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
    public void deleteChallenge(String challengeId, String actorUserId) throws ChallengeSphereException {
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
            challengeInviteRepository.deactivateActiveByChallengeId(challengeId);
            notificationService.invalidateChallengeInvitesForChallenge(challengeId);
            invalidateDeletedChallengeCaches(challenge, allParticipantLinks);
            logger.info("Challenge {} is already deleted; invalidated caches", challengeId);
            return;
        }
        if (!isChallengeDeletable(challenge, allParticipantLinks, System.currentTimeMillis())) {
            throw new ChallengeSphereException(
                    "You can delete a challenge only in its first 3 days and while it has 3 members or fewer",
                    HttpStatus.CONFLICT,
                    logger
            );
        }

        challenge.setActive(false);
        challengeRepository.save(challenge);
        challengeInviteRepository.deactivateActiveByChallengeId(challengeId);
        notificationService.invalidateChallengeInvitesForChallenge(challengeId);

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

    static boolean isChallengeDeletable(
            Challenge challenge,
            List<UserChallenge> participantLinks,
            long nowMillis
    ) {
        if (challenge == null || challenge.getCreatedAt() == null) {
            return false;
        }

        Set<String> activeMemberIds = participantLinks == null
                ? new HashSet<>()
                : participantLinks.stream()
                        .filter(link -> Boolean.TRUE.equals(link.getActive()))
                        .map(UserChallenge::getUserId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
        long ageMillis = nowMillis - challenge.getCreatedAt().getTime();

        return activeMemberIds.size() <= MAX_DELETE_MEMBER_COUNT
                && ageMillis <= MAX_DELETE_AGE_MILLIS;
    }

    @Override
    @Transactional
    public void deleteChallenges(List<String> challengeIds) {
        try {
            logger.info("Bulk update on challenges {}", challengeIds);

            List<Challenge> challenges = challengeRepository.findAllById(challengeIds).stream().toList();
            for (Challenge challenge : challenges) {
                challenge.setActive(false);
                challengeInviteRepository.deactivateActiveByChallengeId(challenge.getId());
                notificationService.invalidateChallengeInvitesForChallenge(challenge.getId());
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

            Map<String, List<UserChallenge>> participantsByChallengeId = allParticipantLinks.stream()
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

        String challengeId = challenge.getId();
        cacheService.clearChallengeCache(challengeId);
        cacheService.invalidateChallengeParticipants(challengeId);
        cacheService.invalidateHashtagChallenges();
        cacheService.invalidateAllFeeds();

        Set<String> affectedUserIds = new HashSet<>();
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
    public void disableChallengesForUsers(List<String> userIds) throws ChallengeSphereException {
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
            for (Challenge challenge : challenges) {
                userChallengeRepository.reconcileParticipationQuotaForChallenge(challenge);
            }

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
        String challengeId = request.getChallengeId();
        List<String> userIds = request.getUserIds();

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

            String ownerUserId = challenge.getCurrentOwnerId() != null ? challenge.getCurrentOwnerId() : challenge.getCreatorId();
            for (String userId : userIds) {
                if (!canJoinChallenge(challenge, ownerUserId, userId, verifiedInvitation)) {
                    throw new ChallengeSphereException(
                            "Not authorized to join this challenge",
                            HttpStatus.FORBIDDEN,
                            logger
                    );
                }
            }

            // Batch validation: Check which users exist (single query)
            List<String> existingUserIds = userRepository.findAllById(userIds).stream()
                    .map(User::getId)
                    .toList();
            Set<String> existingUserIdSet = new HashSet<>(existingUserIds);

            // Find non-existent users
            List<String> nonExistentUserIds = userIds.stream()
                    .filter(userId -> !existingUserIdSet.contains(userId))
                    .collect(Collectors.toList());

            if (!nonExistentUserIds.isEmpty()) {
                logger.warn("Users not found with ids: {}", nonExistentUserIds);
            }

            // Batch check: Find existing UserChallenge associations (single query)
            List<UserChallenge> existingAssociations = userChallengeRepository.findByChallengeId(challengeId);
            Map<String, UserChallenge> existingByUserId = existingAssociations.stream()
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

            Set<String> alreadyJoinedUserIds = existingByUserId.values().stream()
                    .filter(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive()))
                    .map(UserChallenge::getUserId)
                    .collect(Collectors.toSet());

            if (!alreadyJoinedUserIds.isEmpty()) {
                logger.info("Users {} are already members of challenge {}", alreadyJoinedUserIds, challengeId);
            }

            // Determine which users can successfully join
            List<String> successfulUserIds = existingUserIds.stream()
                    .filter(userId -> !alreadyJoinedUserIds.contains(userId))
                    .collect(Collectors.toList());

            for (String userId : successfulUserIds.stream().sorted().toList()) {
                User joiningUser = userRepository.findByIdForUpdate(userId)
                        .orElseThrow(() -> new ChallengeSphereException(
                                "User not found with id: " + userId,
                                HttpStatus.NOT_FOUND,
                                logger
                        ));
                enforceActiveChallengeParticipationLimit(
                        joiningUser,
                        Boolean.TRUE.equals(challenge.getIsPrivate())
                );
            }

            // Determine failed users (non-existent + already joined)
            List<String> failedUserIds = new ArrayList<>();
            failedUserIds.addAll(nonExistentUserIds);
            failedUserIds.addAll(alreadyJoinedUserIds);

            // Batch insert/update: Create new associations and reactivate previously inactive links.
            if (!successfulUserIds.isEmpty()) {
                Timestamp now = new Timestamp(System.currentTimeMillis());
                List<UserChallenge> userChallenges = new ArrayList<>();
                for (String userId : successfulUserIds) {
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

                List<String> persistedUserIds = new ArrayList<>(userChallenges.size());
                for (UserChallenge userChallenge : userChallenges) {
                    try {
                        userChallengeRepository.save(userChallenge);
                        persistedUserIds.add(userChallenge.getUserId());
                    } catch (ParticipationQuotaExceededException quotaExceeded) {
                        failedUserIds.add(userChallenge.getUserId());
                        logger.info(
                                "User {} could not join challenge {} because the active participation quota is full",
                                userChallenge.getUserId(),
                                challengeId
                        );
                    }
                }
                successfulUserIds = List.copyOf(persistedUserIds);
                logger.info("Successfully joined {} users to challenge {}", successfulUserIds.size(), challengeId);
                cacheService.invalidateChallengeParticipants(challengeId);
                cacheService.invalidateRecentAndTrendingChallenges();
                cacheService.invalidateHashtagChallenges();

                // Restore ranking history; created challenges do not count as accepted challenges.
                for (String userId : successfulUserIds) {
                    cacheService.invalidateTimeline(userId);
                    leaderboardService.reactivateParticipation(challengeId, userId);
                    if (!userId.equals(challenge.getCreatorId())) {
                        updateChallengesAcceptedCountAsync(userId, true);
                    }
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

        } catch (ParticipationQuotaExceededException e) {
            throw participationQuotaConflict(e);
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
        String challengeId = request.getChallengeId();
        List<String> userIds = request.getUserIds();

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
            Map<String, UserChallenge> userChallengeMap = existingAssociations.stream()
                    .filter(uc -> userIds.contains(uc.getUserId()))
                    .collect(Collectors.toMap(UserChallenge::getUserId, uc -> uc));

            // Determine which users successfully have associations to deactivate
            List<String> successfulUserIds = new ArrayList<>();
            List<String> failedUserIds = new ArrayList<>();
            List<UserChallenge> toDeactivate = new ArrayList<>();

            // Participation is independent from creator attribution and ownership. The
            // current owner may stop participating while retaining management access.
            for (String userId : userIds) {
                if (userChallengeMap.containsKey(userId)) {
                    UserChallenge userChallenge = userChallengeMap.get(userId);
                    // Only deactivate if currently active
                    if (userChallenge.getActive()) {
                        successfulUserIds.add(userId);
                        userChallenge.setActive(false);
                        if (Boolean.TRUE.equals(challenge.getIsPrivate())) {
                            userChallenge.setFavorite(false);
                            userChallenge.setFavoritedAt(null);
                        }
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
                for (String userId : successfulUserIds) {
                    cacheService.invalidateTimeline(userId);
                }
                cacheService.invalidateChallengeParticipants(challengeId);
                cacheService.invalidateRecentAndTrendingChallenges();
                cacheService.invalidateHashtagChallenges();

                // Hide departed users from rankings; created challenges never counted as accepted.
                for (String userId : successfulUserIds) {
                    leaderboardService.deactivateParticipation(challengeId, userId);
                    if (!userId.equals(challenge.getCreatorId())) {
                        updateChallengesAcceptedCountAsync(userId, false);
                    }
                    if (Boolean.TRUE.equals(challenge.getIsPrivate())) {
                        challengeInviteRepository.deactivateActiveByChallengeIdAndRecipientUserId(
                                challengeId, userId);
                        notificationService.invalidateChallengeInviteForRecipient(challengeId, userId);
                    }
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
            String challengeId,
            String actorUserId,
            String participantUserId,
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
            if (normalizedReason.isEmpty() || normalizedReason.length() > 500) {
                throw new ChallengeSphereException(
                        "A removal reason is required and must not exceed 500 characters",
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
            membership.setFavorite(false);
            membership.setFavoritedAt(null);
            membership.setRemovalReason(normalizedReason);
            membership.setRemovedAt(new Timestamp(System.currentTimeMillis()));
            membership.setRemovedByUserId(actorUserId);
            userChallengeRepository.save(membership);
            if (Boolean.TRUE.equals(challenge.getIsPrivate())) {
                challengeInviteRepository.deactivateActiveByChallengeIdAndRecipientUserId(
                        challengeId, participantUserId);
                notificationService.invalidateChallengeInviteForRecipient(challengeId, participantUserId);
            }

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
            notificationService.notifyParticipantRemoved(
                    challenge,
                    participantUserId,
                    actorUserId,
                    normalizedReason);

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
        String challengeId = request.getChallengeId();
        String currentUserId = request.getCurrentUserId();
        int limit = normalizeLimit(request.getLimit(), 50);
        String queryBinding = cursorBinding(
                "challenge:participants:v2",
                challengeId,
                currentUserId,
                limit
        );

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

            Map<String, AttributeValue> continuation = decodeContinuation(
                    request.getCursor(), queryBinding
            );
            List<User> users = new ArrayList<>(limit);
            int pageReads = 0;
            while (users.size() < limit && pageReads++ < MAX_FILTER_PAGE_READS) {
                DynamoPage<UserChallenge> page =
                        userChallengeRepository.findActiveByChallengeIdOrderedByUserPage(
                                challengeId,
                                limit - users.size(),
                                continuation
                        );
                List<String> orderedUserIds = page.items().stream()
                        .map(UserChallenge::getUserId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
                Map<String, User> usersById = userRepository.findAllById(orderedUserIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
                Set<String> blockedUserIds = userBlockRepository.findActiveBlockedCounterpartIds(
                        currentUserId,
                        orderedUserIds
                );
                orderedUserIds.stream()
                        .filter(userId -> !blockedUserIds.contains(userId))
                        .map(usersById::get)
                        .filter(java.util.Objects::nonNull)
                        .limit(limit - users.size())
                        .forEach(users::add);
                continuation = page.lastEvaluatedKey();
                if (continuation.isEmpty() || page.items().isEmpty()) {
                    break;
                }
            }
            boolean hasMore = !continuation.isEmpty();
            int totalParticipants = userChallengeRepository.countActiveByChallengeId(challengeId);

            logger.info("Found {} visible active participants for challenge {}", users.size(), challengeId);

            return ChallengeParticipantsResponse.create(
                    challengeId,
                    withReadableParticipantProfilePhotos(users),
                    totalParticipants,
                    encodeContinuation(queryBinding, continuation),
                    hasMore
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
        String currentUserId = request.getCurrentUserId();
        int limit = normalizeLimit(request.getLimit(), 10);
        String queryBinding = cursorBinding(
                "challenge:hashtags:v2",
                currentUserId,
                hashtags,
                limit
        );

        logger.info("Getting challenges by hashtag filter for userId={}, limit={}",
                currentUserId, limit);

        try {
            if (currentUserId == null || !userRepository.existsById(currentUserId)) {
                return ChallengesByHashtagResponse.create(hashtags, List.of(), null, false);
            }
            if (hashtags == null || hashtags.isEmpty() || hashtags.size() > MAX_TAG_FILTER_VALUES) {
                throw new ChallengeSphereException(
                        "Between 1 and 20 hashtags or categories are required",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            Map<String, Map<String, AttributeValue>> continuation = decodeShardContinuation(
                    request.getCursor(), queryBinding
            );

            // Category chips are backed by this endpoint in the app, so the
            // repository merges both exact hashtag and exact category partitions.
            List<Challenge> matchingChallenges = new ArrayList<>(limit);
            int candidatesExamined = 0;
            int pageReads = 0;
            boolean hasMore = true;
            search:
            while (matchingChallenges.size() < limit
                    && candidatesExamined < MAX_FILTER_CANDIDATES
                    && pageReads++ < MAX_FILTER_PAGE_READS
                    && hasMore) {
                int candidateLimit = candidateWindow(
                        limit - matchingChallenges.size(),
                        MAX_FILTER_CANDIDATES - candidatesExamined
                );
                ChallengeRepository.ShardedChallengePage page;
                try {
                    page = challengeRepository.findByTagsPage(
                            hashtags,
                            candidateLimit,
                            continuation
                    );
                } catch (IllegalArgumentException invalidState) {
                    throw new ChallengeSphereException(
                            "Invalid cursor", HttpStatus.BAD_REQUEST, logger
                    );
                }
                List<Challenge> candidates = page.candidates().stream()
                        .map(ChallengeRepository.ShardedChallengeCandidate::challenge)
                        .toList();
                Set<String> visibleIds = visibleActiveChallengeIds(candidates, currentUserId);
                for (ChallengeRepository.ShardedChallengeCandidate candidate : page.candidates()) {
                    candidatesExamined++;
                    Challenge challenge = candidate.challenge();
                    continuation = candidate.continuation();
                    hasMore = candidate.hasMore();
                    if (Boolean.TRUE.equals(challenge.getActive())
                            && !isExpiredChallenge(challenge)
                            && visibleIds.contains(challenge.getId())) {
                        matchingChallenges.add(challenge);
                        if (matchingChallenges.size() == limit) {
                            break search;
                        }
                    }
                    if (candidatesExamined == MAX_FILTER_CANDIDATES) {
                        break search;
                    }
                }
                if (page.candidates().isEmpty()) {
                    continuation = page.continuation();
                    hasMore = page.hasMore();
                }
            }

            if (matchingChallenges.isEmpty()) {
                logger.info("No active challenges found for hashtag filter");
                return ChallengesByHashtagResponse.create(
                        hashtags,
                        List.of(),
                        encodeShardContinuation(queryBinding, continuation, hasMore),
                        hasMore
                );
            }

            List<String> challengeIds = matchingChallenges.stream()
                    .map(Challenge::getId)
                    .toList();
            Map<String, Integer> participantCountMap =
                    userChallengeRepository.countActiveByChallengeIds(challengeIds);

            // Preserve newest-first challenge ordering while adding participant counts.
            List<ChallengesByHashtagResponse.ChallengeWithParticipantCount> challengeWithCounts =
                    withReadableCoverPhotoUrls(matchingChallenges).stream()
                    .map(challenge -> new ChallengesByHashtagResponse.ChallengeWithParticipantCount(
                            challenge,
                            participantCountMap.getOrDefault(challenge.getId(), 0)
                    ))
                    .toList();

            logger.info("Found {} challenges for hashtag filter", challengeWithCounts.size());

            ChallengesByHashtagResponse response = ChallengesByHashtagResponse.create(
                    hashtags,
                    challengeWithCounts,
                    encodeShardContinuation(queryBinding, continuation, hasMore),
                    hasMore
            );

            return response;

        } catch (ChallengeSphereException e) {
            throw e;
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
        String currentUserId = request.getCurrentUserId();
        Boolean activeFilter = request.getActive();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        int limit = normalizeLimit(request.getLimit(), 10);
        String queryBinding = cursorBinding(
                "challenge:popular:v2",
                currentUserId,
                activeFilter,
                startDate,
                endDate,
                limit
        );

        logger.info("Getting popular challenges - currentUserId: {}, active: {}, startDate: {}, endDate: {}, limit: {}",
                currentUserId, activeFilter, startDate, endDate, limit);

        try {
            if (currentUserId == null || !userRepository.existsById(currentUserId)) {
                return PopularChallengesResponse.create(List.of(), null, false);
            }
            Map<String, AttributeValue> continuation = decodeContinuation(
                    request.getCursor(), queryBinding
            );
            // canViewChallengeDiscovery has always limited this endpoint to
            // active challenges, so active=false remains an empty result.
            if (Boolean.FALSE.equals(activeFilter)) {
                return PopularChallengesResponse.create(List.of(), null, false);
            }
            ChallengeDiscoveryPage page = findPublicDiscoveryPage(
                    currentUserId,
                    limit,
                    continuation,
                    startDate,
                    endDate
            );
            List<PopularChallengesResponse.ChallengeWithParticipantCount> challengeWithCounts =
                    withReadableCoverPhotoUrls(page.challenges()).stream()
                    .map(challenge -> new PopularChallengesResponse.ChallengeWithParticipantCount(
                            challenge,
                            userChallengeRepository.countActiveByChallengeId(challenge.getId())
                    ))
                    .toList();
            logger.info("Found {} public challenges matching criteria", challengeWithCounts.size());
            return PopularChallengesResponse.create(
                    challengeWithCounts,
                    encodeContinuation(queryBinding, page.continuation()),
                    page.hasMore()
            );

        } catch (ChallengeSphereException e) {
            throw e;
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
    public RecentChallengesResponse getRecentChallenges(
            String currentUserId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 10);
        String queryBinding = cursorBinding(
                "challenge:recent:v2",
                currentUserId,
                boundedLimit
        );
        logger.info("Getting recent challenges - currentUserId: {}, limit: {}",
                currentUserId, boundedLimit);

        try {
            if (currentUserId == null || !userRepository.existsById(currentUserId)) {
                return RecentChallengesResponse.empty();
            }
            Map<String, AttributeValue> continuation = decodeContinuation(cursor, queryBinding);
            ChallengeDiscoveryPage page = findPublicDiscoveryPage(
                    currentUserId,
                    boundedLimit,
                    continuation,
                    null,
                    null
            );
            List<RecentChallengesResponse.ChallengeInfo> challengeInfos =
                    withReadableCoverPhotoUrls(page.challenges()).stream()
                    .map(challenge -> {
                        int participantCount = userChallengeRepository.countActiveByChallengeId(
                                challenge.getId()
                        );
                        return RecentChallengesResponse.ChallengeInfo.fromChallenge(
                                challenge, 0, 0, participantCount);
                    })
                    .toList();
            return RecentChallengesResponse.create(
                    challengeInfos,
                    encodeContinuation(queryBinding, page.continuation()),
                    page.hasMore()
            );

        } catch (ChallengeSphereException e) {
            throw e;
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
    public CursorPageResponse<String> getTrendingChallengeIds(int limit, String cursor) {
        logger.info("Getting trending challenge IDs, limit: {}", limit);

        int boundedLimit = normalizeLimit(limit, 10);
        String queryBinding = cursorBinding("challenge:trending-ids:v2", boundedLimit);
        if (cursor != null && !cursor.isBlank()) {
            DynamoDbCursorCodec.CursorState state = cursorCodec.decode(cursor, queryBinding);
            if (!state.lastEvaluatedKey().isEmpty() || !state.shardState().isEmpty()) {
                throw new IllegalArgumentException("Trending challenge cursor state is invalid");
            }
        }

        return CursorPageResponse.empty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Challenge updateChallenge(String id, ChallengeUpdateRequest request, String actorUserId, boolean admin) throws ChallengeSphereException {
        logger.info("Updating challenge id: {}", id);

        try (var challengeLease = challengeRepository.acquireChallengeLease(id)) {
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
        boolean becomingPrivate = Boolean.TRUE.equals(request.getIsPrivate())
                && !Boolean.TRUE.equals(challenge.getIsPrivate());
        boolean changingVisibility = request.getIsPrivate() != null
                && !java.util.Objects.equals(request.getIsPrivate(), challenge.getIsPrivate());
        if (changingVisibility
                && Boolean.TRUE.equals(challenge.getActive())
                && !isExpiredChallenge(challenge)) {
            boolean targetPrivate = Boolean.TRUE.equals(request.getIsPrivate());
            List<String> activeParticipantIds = userChallengeRepository.findActiveByChallengeId(id).stream()
                    .filter(membership -> !Boolean.TRUE.equals(membership.getCompleted()))
                    .map(UserChallenge::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            for (String participantId : activeParticipantIds) {
                User participant = userRepository.findByIdForUpdate(participantId)
                        .orElseThrow(() -> new ChallengeSphereException(
                                "User not found with id: " + participantId,
                                HttpStatus.NOT_FOUND,
                                logger
                        ));
                enforceActiveChallengeParticipationLimit(participant, targetPrivate);
            }
        }
        if (request.getIsPrivate() != null) {
            challenge.setIsPrivate(request.getIsPrivate());
        }
        String effectiveOwnerId = challenge.getCurrentOwnerId() != null
                ? challenge.getCurrentOwnerId()
                : challenge.getCreatorId();
        if (Boolean.TRUE.equals(challenge.getIsPrivate())
                && request.getInvitedUserIds() != null
                && !request.getInvitedUserIds().isEmpty()
                && !java.util.Objects.equals(actorUserId, effectiveOwnerId)) {
            throw new ChallengeSphereException(
                    "Only the current challenge owner can invite users to a private challenge",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (becomingPrivate) {
            List<UserChallenge> staleFormerMemberFavorites = userChallengeRepository
                    .findByChallengeId(id).stream()
                    .filter(membership -> !Boolean.TRUE.equals(membership.getActive()))
                    .filter(membership -> !membership.getUserId().equals(challenge.getCreatorId()))
                    .filter(membership -> !membership.getUserId().equals(challenge.getCurrentOwnerId()))
                    .filter(membership -> Boolean.TRUE.equals(membership.getFavorite()))
                    .peek(membership -> {
                        membership.setFavorite(false);
                        membership.setFavoritedAt(null);
                    })
                    .toList();
            if (!staleFormerMemberFavorites.isEmpty()) {
                userChallengeRepository.saveAll(staleFormerMemberFavorites);
            }
            // Public-challenge invitations may have been issued by participants
            // and may contain public metadata. They cannot cross into the new
            // owner-only, opaque private-invite boundary.
            notificationService.invalidateChallengeInvitesForChallenge(id);
            challengeInviteRepository.deactivateUnboundActiveByChallengeId(id);
        }
        if (request.getChallengeMode() != null) {
            challenge.setChallengeMode(normalizeChallengeMode(request.getChallengeMode()));
        }
        // Flexible/text-only proof is paused. Preserve the request field for a
        // future rollout, but persist the current universal photo requirement.
        challenge.setProofMode(normalizeProofMode(request.getProofMode()));
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
            String mediaOwnerId = actorUserId != null
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
            Set<String> invitedUserIds = request.getInvitedUserIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(userId -> !userId.isBlank())
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

        boolean quotaClassificationChanged = changingVisibility
                || request.getActive() != null
                || request.getChallengeMode() != null
                || request.getNumberOfDays() != null;
        if (quotaClassificationChanged
                && Boolean.TRUE.equals(challenge.getActive())
                && !isExpiredChallenge(challenge)) {
            // Reserve the target visibility/tier slots before exposing a
            // classification that would otherwise be under-counted.
            reconcileParticipationQuota(challenge);
        }

        Challenge updated = challengeRepository.save(challenge);
        if (quotaClassificationChanged) {
            // A second pass releases source slots and catches memberships
            // created while the challenge classification was being changed.
            reconcileParticipationQuota(updated);
        }
        cacheService.invalidateChallenge(id);
        cacheService.invalidateAllFeeds();
        notifyChallengeInvitesAfterCommit(
                updated,
                actorUserId,
                request.getInvitedUserIds()
        );
        logger.info("Challenge {} updated successfully", id);
        return withReadableCoverPhotoUrls(updated);
        }
    }

    private void reconcileParticipationQuota(Challenge challenge) throws ChallengeSphereException {
        try {
            userChallengeRepository.reconcileParticipationQuotaForChallenge(challenge);
        } catch (ParticipationQuotaExceededException quotaExceeded) {
            throw participationQuotaConflict(quotaExceeded);
        }
    }

    private ChallengeSphereException participationQuotaConflict(
            ParticipationQuotaExceededException quotaExceeded
    ) {
        return new ChallengeSphereException(
                quotaExceeded.getMessage(),
                HttpStatus.CONFLICT,
                logger
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inviteChallengeUsers(String challengeId, String actorUserId, List<String> invitedUserIds)
            throws ChallengeSphereException {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        String effectiveOwnerId = challenge.getCurrentOwnerId() != null
                ? challenge.getCurrentOwnerId()
                : challenge.getCreatorId();
        boolean owner = actorUserId != null && actorUserId.equals(effectiveOwnerId);
        UserChallenge actorMembership = owner || actorUserId == null
                ? null
                : userChallengeRepository.findByUserIdAndChallengeId(actorUserId, challengeId).orElse(null);
        boolean activeParticipant = actorMembership != null
                && Boolean.TRUE.equals(actorMembership.getActive())
                && !Boolean.TRUE.equals(actorMembership.getBanned());
        if (Boolean.TRUE.equals(challenge.getIsPrivate()) && !owner) {
            throw new ChallengeSphereException(
                    "Only the challenge owner can invite users to a private challenge",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (!owner && !activeParticipant) {
            throw new ChallengeSphereException(
                    "Only the challenge creator or active participants can invite app friends",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (!Boolean.TRUE.equals(challenge.getActive()) || isExpiredChallenge(challenge)) {
            throw new ChallengeSphereException(
                    "This challenge is no longer accepting invitations",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        Set<String> requestedRecipientIds = invitedUserIds == null
                ? Set.of()
                : invitedUserIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(userId -> !userId.isBlank())
                        .filter(userId -> !userId.equals(actorUserId))
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (requestedRecipientIds.isEmpty()) {
            throw new ChallengeSphereException(
                    "Select at least one friend to invite",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        requestedRecipientIds.removeIf(recipientUserId ->
                userBlockRepository.existsActiveBlockBetween(actorUserId, recipientUserId));
        if (requestedRecipientIds.isEmpty()) {
            throw new ChallengeSphereException(
                    "No selected friends are available to invite",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        List<UserChallenge> recipientMemberships = userChallengeRepository.findByChallengeId(challengeId).stream()
                .filter(membership -> requestedRecipientIds.contains(membership.getUserId()))
                .toList();
        Set<String> alreadyActiveRecipientIds = recipientMemberships.stream()
                .filter(membership -> Boolean.TRUE.equals(membership.getActive()))
                .map(UserChallenge::getUserId)
                .collect(Collectors.toSet());
        Set<String> bannedRecipientIds = recipientMemberships.stream()
                .filter(membership -> Boolean.TRUE.equals(membership.getBanned()))
                .map(UserChallenge::getUserId)
                .collect(Collectors.toSet());

        boolean originalCreator = actorUserId != null && actorUserId.equals(challenge.getCreatorId());
        if (originalCreator && !bannedRecipientIds.isEmpty()) {
            List<UserChallenge> reinstatedMemberships = recipientMemberships.stream()
                    .filter(membership -> bannedRecipientIds.contains(membership.getUserId()))
                    .filter(membership -> !Boolean.TRUE.equals(membership.getActive()))
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
            bannedRecipientIds.removeAll(
                    reinstatedMemberships.stream().map(UserChallenge::getUserId).toList()
            );
        }

        requestedRecipientIds.removeAll(alreadyActiveRecipientIds);
        requestedRecipientIds.removeAll(bannedRecipientIds);
        if (requestedRecipientIds.isEmpty()) {
            throw new ChallengeSphereException(
                    "No selected friends are available to invite",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        notifyChallengeInvitesAfterCommit(
                challenge,
                actorUserId,
                requestedRecipientIds
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengeDetailResponse getChallengeDetail(String challengeId, String currentUserId) throws ChallengeSphereException {
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
    public ChallengeProgressResponse getChallengeProgress(String challengeId, String userId) throws ChallengeSphereException {
        return getChallengeProgress(challengeId, userId, LocalDate.now(java.time.ZoneOffset.UTC));
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengeProgressResponse getChallengeProgress(
            String challengeId,
            String userId,
            LocalDate currentDate
    ) throws ChallengeSphereException {
        LocalDate effectiveCurrentDate = currentDate != null
                ? currentDate
                : LocalDate.now(java.time.ZoneOffset.UTC);
        logger.info("Fetching challenge progress: challengeId={}, userId={}, localDate={}",
                challengeId, userId, effectiveCurrentDate);
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
            int streakThroughDay = resolveStreakThroughDay(
                    challenge,
                    submittedDayNumbers,
                    totalDays,
                    effectiveCurrentDate
            );
            int currentStreak = StreakCalculator.currentStreak(submittedDayNumbers, streakThroughDay);
            int longestStreak = StreakCalculator.longestStreak(submittedDayNumbers);
            int daysCompleted = submittedDayNumbers.size();
            int completionPct = totalDays > 0 ? (int) Math.round((daysCompleted * 100.0) / totalDays) : 0;

            UserChallenge participation = userChallengeRepository
                    .findByUserIdAndChallengeId(userId, challengeId)
                    .filter(membership -> Boolean.TRUE.equals(membership.getActive())
                            && !Boolean.TRUE.equals(membership.getBanned()))
                    .orElse(null);
            boolean privateLobby = isPrivateLobby(challenge,
                    Boolean.TRUE.equals(challenge.getIsPrivate())
                            ? challengeCrewLifecycleRepository.findById(challengeId).orElse(null)
                            : null);
            ChallengeMomentumResponse momentum = buildMomentum(
                    challenge,
                    participation,
                    submittedDayNumbers,
                    totalDays,
                    privateLobby,
                    effectiveCurrentDate
            );

            ChallengeProgressResponse response = new ChallengeProgressResponse();
            response.setChallengeId(challengeId);
            response.setChallengeTitle(challenge.getTitle());
            response.setTotalDays(totalDays);
            response.setDaysCompleted(daysCompleted);
            response.setCompletionPercentage(completionPct);
            response.setCurrentStreak(currentStreak);
            response.setLongestStreak(longestStreak);
            response.setMomentum(momentum);
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
    @Transactional
    public ChallengeFavoriteResponse setChallengeFavorite(
            String challengeId,
            String userId,
            boolean favorite
    ) throws ChallengeSphereException {
        try (var challengeLease = challengeRepository.acquireChallengeLease(challengeId)) {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));
            try (var membershipLease = userChallengeRepository.acquireMembershipLease(
                    userId, challengeId
            )) {
                UserChallenge membership = userChallengeRepository
                        .findByUserIdAndChallengeId(userId, challengeId)
                        .orElseThrow(() -> new ChallengeSphereException(
                                "Only created or joined challenges can be favorited",
                                HttpStatus.FORBIDDEN,
                                logger
                        ));

                if (Boolean.TRUE.equals(membership.getBanned())) {
                    throw new ChallengeSphereException(
                            "This challenge is no longer available to you",
                            HttpStatus.FORBIDDEN,
                            logger
                    );
                }
                if (favorite && !canKeepFavoriteChallenge(challenge, membership, userId)) {
                    throw new ChallengeSphereException(
                            "This challenge is no longer available to favorite",
                            HttpStatus.FORBIDDEN,
                            logger
                    );
                }

                if (Boolean.TRUE.equals(membership.getFavorite()) != favorite) {
                    membership.setFavorite(favorite);
                    membership.setFavoritedAt(favorite
                            ? new Timestamp(System.currentTimeMillis())
                            : null);
                    userChallengeRepository.save(membership);
                }
                return ChallengeFavoriteResponse.of(challengeId, favorite);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MyChallengesResponse getMyChallenges(
            String userId,
            String currentUserId,
            String status,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        return getMyChallenges(
                userId,
                currentUserId,
                status,
                limit,
                cursor,
                LocalDate.now(java.time.ZoneOffset.UTC)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public MyChallengesResponse getMyChallenges(
            String userId,
            String currentUserId,
            String status,
            int limit,
            String cursor,
            LocalDate currentDate
    ) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 10);
        LocalDate requestedCurrentDate = currentDate != null
                ? currentDate
                : LocalDate.now(java.time.ZoneOffset.UTC);
        logger.info("Fetching my challenges: userId={}, viewerId={}, status={}, limit={}, localDate={}",
                userId, currentUserId, status, boundedLimit, requestedCurrentDate);
        try {
            ensureFollowAccess(userId, currentUserId, "challenges");
            boolean selfView = java.util.Objects.equals(userId, currentUserId);
            // The supplied device timezone belongs to the authenticated viewer. Keep legacy
            // cross-profile completion status on the server fallback date and expose no
            // participant-only motivation for those views.
            LocalDate effectiveCurrentDate = selfView
                    ? requestedCurrentDate
                    : LocalDate.now(java.time.ZoneOffset.UTC);

            boolean favoriteFilter = "favorite".equalsIgnoreCase(status)
                    || "favourite".equalsIgnoreCase(status);
            boolean inactiveFilter = "inactive".equalsIgnoreCase(status);
            if ((favoriteFilter || inactiveFilter) && !selfView) {
                throw new ChallengeSphereException(
                        "This challenge collection is private to its owner",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }
            String queryBinding = cursorBinding(
                    "challenge:my:v2",
                    userId,
                    currentUserId,
                    status,
                    effectiveCurrentDate,
                    boundedLimit
            );
            Map<String, AttributeValue> continuation = decodeContinuation(cursor, queryBinding);
            List<MyChallengesResponse.ChallengeSummary> summaries = new ArrayList<>();
            int candidatesExamined = 0;
            int pageReads = 0;
            boolean hasMore = true;
            search:
            while (summaries.size() < boundedLimit
                    && candidatesExamined < MAX_FILTER_CANDIDATES
                    && pageReads++ < MAX_FILTER_PAGE_READS
                    && hasMore) {
                int pageLimit = candidateWindow(
                        boundedLimit - summaries.size(),
                        MAX_FILTER_CANDIDATES - candidatesExamined
                );
                DynamoPage<UserChallenge> page = favoriteFilter
                        ? userChallengeRepository.findFavoritesByUserIdPage(
                                userId, pageLimit, continuation
                        )
                        : inactiveFilter
                                ? userChallengeRepository.findByUserIdPage(
                                        userId, pageLimit, continuation
                                )
                                : userChallengeRepository.findActiveByUserIdPage(
                                        userId, pageLimit, continuation
                                );
                candidatesExamined += page.items().size();
                Map<String, MyChallengesResponse.ChallengeSummary> summariesByMembershipId =
                        buildMyChallengeSummaries(
                                page.items(),
                                userId,
                                currentUserId,
                                status,
                                selfView,
                                favoriteFilter,
                                inactiveFilter,
                                effectiveCurrentDate
                        );
                for (int index = 0; index < page.items().size(); index++) {
                    UserChallenge membership = page.items().get(index);
                    MyChallengesResponse.ChallengeSummary summary =
                            summariesByMembershipId.get(membership.getId());
                    continuation = membershipContinuation(
                            userId, membership, favoriteFilter, inactiveFilter
                    );
                    if (summary != null) {
                        summaries.add(summary);
                        if (summaries.size() == boundedLimit) {
                            hasMore = index + 1 < page.items().size()
                                    || !page.lastEvaluatedKey().isEmpty();
                            break search;
                        }
                    }
                }
                continuation = page.lastEvaluatedKey();
                hasMore = !continuation.isEmpty();
                if (page.items().isEmpty()) {
                    break;
                }
            }

            return MyChallengesResponse.create(
                    summaries,
                    encodeContinuation(
                            queryBinding,
                            hasMore ? continuation : Map.of()
                    ),
                    hasMore
            );
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

    private String normalizeProofMode(String proofMode) throws ChallengeSphereException {
        // Keep accepting the field so old clients remain compatible, while the
        // temporary product policy makes every newly written challenge photo-only.
        return Challenge.PROOF_MODE_PHOTO_REQUIRED;
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
        return isExpiredChallenge(challenge, LocalDate.now());
    }

    private boolean isExpiredChallenge(Challenge challenge, LocalDate currentDate) {
        if (challenge == null || Challenge.isOneOffMode(challenge.getChallengeMode())) {
            return false;
        }
        LocalDate startDate = challenge.getStartDate();
        Integer numberOfDays = challenge.getNumberOfDays();
        if (startDate == null || numberOfDays == null || numberOfDays <= 0) {
            return false;
        }
        LocalDate effectiveCurrentDate = currentDate == null ? LocalDate.now() : currentDate;
        return !effectiveCurrentDate.isBefore(startDate.plusDays(numberOfDays));
    }

    private ChallengeMomentumResponse buildMomentum(
            Challenge challenge,
            UserChallenge participation,
            List<Integer> submittedDays,
            int totalDays,
            boolean privateLobby,
            LocalDate currentDate
    ) {
        if (challenge == null || participation == null) {
            return null;
        }
        int currentDay = challenge.getStartDate() == null
                ? -1
                : (int) java.time.temporal.ChronoUnit.DAYS.between(challenge.getStartDate(), currentDate) + 1;
        boolean submittedToday = submittedDays != null && submittedDays.contains(currentDay);
        ChallengeMomentumPolicy.Input input = new ChallengeMomentumPolicy.Input(
                challenge.getChallengeMode(),
                Boolean.TRUE.equals(challenge.getActive()),
                Boolean.TRUE.equals(participation.getCompleted()),
                Boolean.TRUE.equals(challenge.getIsPrivate()),
                privateLobby,
                challenge.getStartDate(),
                totalDays,
                submittedDays == null ? 0 : submittedDays.size(),
                submittedToday,
                currentDate
        );
        return challengeMomentumPolicy.evaluate(input)
                .map(ChallengeMomentumResponse::from)
                .orElse(null);
    }

    private int resolveStreakThroughDay(
            Challenge challenge,
            List<Integer> submittedDays,
            int totalDays,
            LocalDate currentDate
    ) {
        if (challenge == null || challenge.getStartDate() == null || totalDays <= 0) {
            return submittedDays == null
                    ? 0
                    : submittedDays.stream().max(Integer::compareTo).orElse(0);
        }

        int currentDay = (int) java.time.temporal.ChronoUnit.DAYS.between(
                challenge.getStartDate(),
                currentDate
        ) + 1;
        if (currentDay < 1) {
            return 0;
        }
        if (currentDay > totalDays) {
            return totalDays;
        }

        // The current day is still in progress until it is completed.
        return submittedDays != null && submittedDays.contains(currentDay)
                ? currentDay
                : currentDay - 1;
    }

    private boolean isPrivateLobby(Challenge challenge, ChallengeCrewLifecycle lifecycle) {
        return challenge != null
                && Boolean.TRUE.equals(challenge.getIsPrivate())
                && (lifecycle == null || lifecycle.getStartedAt() == null);
    }

    private MyChallengesResponse.PrivateRank resolvePrivateRank(
            Challenge challenge,
            String userId,
            String viewerUserId,
            boolean oneOff,
            boolean completed,
            boolean privateLobby
    ) {
        if (challenge == null
                || !Boolean.TRUE.equals(challenge.getIsPrivate())
                || !Boolean.TRUE.equals(challenge.getActive())
                || oneOff
                || completed
                || privateLobby) {
            return null;
        }
        try {
            LeaderboardResponse.UserRankDTO userRank = leaderboardService.getUserRank(
                    challenge.getId(), userId, viewerUserId);
            if (userRank == null || userRank.getRank() == null || userRank.getTotalParticipants() == null) {
                return null;
            }
            return MyChallengesResponse.PrivateRank.of(userRank.getRank(), userRank.getTotalParticipants());
        } catch (ChallengeSphereException | RuntimeException exception) {
            logger.warn("Skipping private rank for challengeId={} userId={}: {}",
                    challenge.getId(), userId, exception.getClass().getSimpleName());
            return null;
        }
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

        Map<String, User> ownerUsersById = hydrateChallengeOwnerUsers(challenges);

        return challenges.stream()
                .map(challenge -> withReadableChallengeMedia(
                        challenge,
                        ownerUsersById.get(challenge.getCreatorId()),
                        ownerUsersById
                ))
                .toList();
    }

    private Challenge withReadableCoverPhotoUrls(Challenge challenge) {
        if (challenge == null) {
            return null;
        }

        Map<String, User> ownerUsersById = hydrateChallengeOwnerUsers(List.of(challenge));
        return withReadableChallengeMedia(
                challenge,
                ownerUsersById.get(challenge.getCreatorId()),
                ownerUsersById
        );
    }

    private Challenge withReadableChallengeMedia(
            Challenge challenge,
            User creator,
            Map<String, User> ownerUsersById
    ) {
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
        copy.setProofMode(challenge.getProofMode());
        copy.setCoverPhotoUrls(toReadableCoverPhotoUrls(challenge, ownerUsersById));
        copy.setOfficialTemplateKey(challenge.getOfficialTemplateKey());
        copy.setCohortKey(challenge.getCohortKey());
        return copy;
    }

    private String toReadableProfilePicUrl(String userId, String profilePicUrl) {
        if (profilePicUrl == null || profilePicUrl.isBlank() || mediaUrlService == null) {
            return profilePicUrl;
        }

        try {
            return mediaUrlService.toReadableProfilePhoto(userId, profilePicUrl);
        } catch (Exception e) {
            logger.warn("Failed to prepare readable creator profile photo URL: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private String toReadableCoverPhotoUrls(
            Challenge challenge,
            Map<String, User> ownerUsersById
    ) {
        String rawCoverPhotoUrls = challenge == null ? null : challenge.getCoverPhotoUrls();
        if (rawCoverPhotoUrls == null || rawCoverPhotoUrls.isBlank()) {
            return rawCoverPhotoUrls;
        }
        if (mediaUrlService == null) {
            return null;
        }

        try {
            Set<String> allowedOwnerIds = challengeMediaOwnerIds(challenge, ownerUsersById);
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

    private Map<String, User> hydrateChallengeOwnerUsers(Collection<Challenge> challenges) {
        Set<String> ownerUserIds = new HashSet<>();
        if (challenges != null) {
            for (Challenge challenge : challenges) {
                if (challenge == null) {
                    continue;
                }
                if (challenge.getCreatorId() != null && !challenge.getCreatorId().isBlank()) {
                    ownerUserIds.add(challenge.getCreatorId());
                }
                if (challenge.getCurrentOwnerId() != null && !challenge.getCurrentOwnerId().isBlank()) {
                    ownerUserIds.add(challenge.getCurrentOwnerId());
                }
            }
        }
        if (ownerUserIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return userRepository.findAllById(ownerUserIds).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        user -> user,
                        (left, right) -> left
                ));
    }

    private Set<String> challengeMediaOwnerIds(
            Challenge challenge,
            Map<String, User> ownerUsersById
    ) {
        if (challenge == null) {
            return Set.of();
        }
        Map<String, User> usersById = ownerUsersById == null
                ? new LinkedHashMap<>()
                : ownerUsersById;
        Set<String> allowedOwnerIds = new HashSet<>();
        addChallengeMediaOwnerId(
                allowedOwnerIds,
                challenge.getCreatorId(),
                usersById.get(challenge.getCreatorId())
        );
        addChallengeMediaOwnerId(
                allowedOwnerIds,
                challenge.getCurrentOwnerId(),
                usersById.get(challenge.getCurrentOwnerId())
        );
        return Set.copyOf(allowedOwnerIds);
    }

    private void addChallengeMediaOwnerId(
            Set<String> allowedOwnerIds,
            String currentOwnerId,
            User owner
    ) {
        if (currentOwnerId == null || currentOwnerId.isBlank()) {
            return;
        }
        allowedOwnerIds.add(currentOwnerId);
        if (owner != null
                && currentOwnerId.equals(owner.getId())
                && isNumericLegacyUserId(owner.getLegacyId())) {
            allowedOwnerIds.add(owner.getLegacyId());
        }
    }

    private static boolean isNumericLegacyUserId(String userId) {
        return userId != null
                && !userId.isBlank()
                && userId.chars().allMatch(character -> character >= '0' && character <= '9');
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
            String ownerUserId,
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
            moderationEnforcementService.removeFlaggedMediaAndWarn(ownerUserId, moderationResult);
            throw new ChallengeSphereException(
                    "Challenge cover image removed for violating the media policy. A warning was sent to your notifications.",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return serializedMediaKeys;
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
            String leftId,
            Timestamp rightCreatedAt,
            String rightId
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

        if (leftId == null && rightId == null) {
            return 0;
        }
        if (leftId == null) {
            return 1;
        }
        if (rightId == null) {
            return -1;
        }
        return rightId.compareTo(leftId);
    }

    private static int compareNullableIds(String leftId, String rightId) {
        if (leftId == null && rightId == null) {
            return 0;
        }
        if (leftId == null) {
            return 1;
        }
        if (rightId == null) {
            return -1;
        }
        return leftId.compareTo(rightId);
    }

    private static int normalizeLimit(Integer requestedLimit, int defaultLimit) {
        int resolvedLimit = requestedLimit == null || requestedLimit < 1
                ? defaultLimit
                : requestedLimit;
        return Math.min(resolvedLimit, MAX_LIMIT);
    }

    private static int candidateWindow(int remainingResults, int remainingBudget) {
        int usefulWindow = Math.max(10, Math.max(1, remainingResults) * 2);
        return Math.max(1, Math.min(
                Math.min(100, usefulWindow),
                Math.max(1, remainingBudget)
        ));
    }

    private static String cursorBinding(String endpoint, Object... components) {
        StringBuilder binding = new StringBuilder(endpoint);
        for (Object component : components) {
            binding.append('|');
            appendCursorBindingComponent(binding, component);
        }
        return binding.toString();
    }

    private static void appendCursorBindingComponent(StringBuilder binding, Object component) {
        if (component == null) {
            binding.append("N;");
            return;
        }
        if (component instanceof Collection<?> collection) {
            binding.append('L').append(collection.size()).append('{');
            collection.forEach(value -> appendCursorBindingComponent(binding, value));
            binding.append("};");
            return;
        }
        String value = component.toString();
        binding.append('S').append(value.length()).append(':').append(value).append(';');
    }

    private Map<String, MyChallengesResponse.ChallengeSummary> buildMyChallengeSummaries(
            List<UserChallenge> memberships,
            String userId,
            String currentUserId,
            String status,
            boolean selfView,
            boolean favoriteFilter,
            boolean inactiveFilter,
            LocalDate effectiveCurrentDate
    ) {
        if (memberships == null || memberships.isEmpty()) {
            return Map.of();
        }
        List<String> challengeIds = memberships.stream()
                .map(UserChallenge::getChallengeId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<Challenge> candidates = challengeRepository.findAllById(challengeIds);
        Map<String, UserChallenge> membershipByChallengeId = memberships.stream()
                .collect(Collectors.toMap(
                        UserChallenge::getChallengeId,
                        membership -> membership,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));

        Set<String> visibleIds;
        if (favoriteFilter || inactiveFilter) {
            Set<String> blockedChallengeIds = blockedChallengeIds(candidates, currentUserId);
            visibleIds = candidates.stream()
                    .filter(challenge -> !blockedChallengeIds.contains(challenge.getId()))
                    .filter(challenge -> favoriteFilter
                            ? canKeepFavoriteChallenge(
                                    challenge,
                                    membershipByChallengeId.get(challenge.getId()),
                                    userId
                            )
                            : canViewInactiveChallengeHistory(
                                    challenge,
                                    membershipByChallengeId.get(challenge.getId()),
                                    userId
                            ))
                    .map(Challenge::getId)
                    .collect(Collectors.toUnmodifiableSet());
        } else {
            visibleIds = visibleActiveChallengeIds(candidates, currentUserId);
        }

        List<Challenge> visibleChallenges = candidates.stream()
                .filter(challenge -> visibleIds.contains(challenge.getId()))
                .toList();
        Map<String, Challenge> challengeMap = withReadableCoverPhotoUrls(visibleChallenges)
                .stream()
                .collect(Collectors.toMap(Challenge::getId, challenge -> challenge));
        Map<String, Integer> activeParticipantCountMap =
                userChallengeRepository.countActiveByChallengeIds(challengeMap.keySet());
        List<String> privateChallengeIds = challengeMap.values().stream()
                .filter(challenge -> Boolean.TRUE.equals(challenge.getIsPrivate()))
                .map(Challenge::getId)
                .toList();
        Map<String, ChallengeCrewLifecycle> privateLifecycleMap = privateChallengeIds.isEmpty()
                ? Map.of()
                : challengeCrewLifecycleRepository.findAllById(privateChallengeIds).stream()
                        .collect(Collectors.toMap(
                                ChallengeCrewLifecycle::getChallengeId,
                                lifecycle -> lifecycle
                        ));

        boolean filterByCompletion = status != null
                && !status.isBlank()
                && !favoriteFilter
                && !inactiveFilter;
        boolean requestedCompleted = "completed".equalsIgnoreCase(status);
        Map<String, MyChallengesResponse.ChallengeSummary> result = new LinkedHashMap<>();
        for (UserChallenge membership : memberships) {
            Challenge challenge = challengeMap.get(membership.getChallengeId());
            if (challenge == null) {
                continue;
            }
            List<Submission> submissions = submissionRepository.findActiveByUserIdAndChallengeId(
                    userId,
                    membership.getChallengeId()
            );
            boolean oneOff = Challenge.isOneOffMode(challenge.getChallengeMode());
            int totalDays = oneOff ? 1 : challenge.getNumberOfDays();
            List<Integer> submittedDays = distinctSubmittedDays(submissions, totalDays);
            int daysCompleted = oneOff && !submittedDays.isEmpty()
                    ? 1
                    : submittedDays.size();
            int completionPct = totalDays > 0
                    ? (int) Math.round((daysCompleted * 100.0) / totalDays)
                    : 0;

            MyChallengesResponse.ChallengeSummary summary =
                    MyChallengesResponse.ChallengeSummary.from(challenge);
            summary.setDaysCompleted(daysCompleted);
            int streakThroughDay = resolveStreakThroughDay(
                    challenge,
                    submittedDays,
                    totalDays,
                    effectiveCurrentDate
            );
            summary.setCurrentStreak(StreakCalculator.currentStreak(
                    submittedDays,
                    streakThroughDay
            ));
            summary.setCompletionPercentage(completionPct);
            summary.setJoinedAt(membership.getJoinedAt());
            summary.setCompleted(oneOff
                    ? daysCompleted == 1
                    : Boolean.TRUE.equals(membership.getCompleted())
                            || isExpiredChallenge(challenge, effectiveCurrentDate));
            if (filterByCompletion && summary.isCompleted() != requestedCompleted) {
                continue;
            }
            summary.setFavorite(selfView && Boolean.TRUE.equals(membership.getFavorite()));
            summary.setParticipationActive(Boolean.TRUE.equals(membership.getActive()));
            int participantCount = activeParticipantCountMap.getOrDefault(challenge.getId(), 0);
            summary.setParticipantCount(participantCount);
            summary.setActiveParticipantCount(participantCount);
            boolean privateLobby = isPrivateLobby(
                    challenge,
                    privateLifecycleMap.get(challenge.getId())
            );
            summary.setMomentum(selfView ? buildMomentum(
                    challenge,
                    membership,
                    submittedDays,
                    totalDays,
                    privateLobby,
                    effectiveCurrentDate
            ) : null);
            summary.setPrivateRank(selfView ? resolvePrivateRank(
                    challenge,
                    userId,
                    currentUserId,
                    oneOff,
                    summary.isCompleted(),
                    privateLobby
            ) : null);
            result.put(membership.getId(), summary);
        }
        return Map.copyOf(result);
    }

    private Map<String, AttributeValue> membershipContinuation(
            String userId,
            UserChallenge membership,
            boolean favoriteFilter,
            boolean inactiveFilter
    ) {
        String sortKey;
        if (favoriteFilter) {
            sortKey = ChallengeRepositoryKeys.membershipFavoriteSk(
                    membership.getFavoritedAt(), membership.getJoinedAt(), membership.getId()
            );
        } else if (inactiveFilter) {
            sortKey = ChallengeRepositoryKeys.membershipAllSk(
                    membership.getJoinedAt(), membership.getId()
            );
        } else {
            sortKey = ChallengeRepositoryKeys.membershipStatusSk(
                    "ACTIVE", membership.getJoinedAt(), membership.getId()
            );
        }
        return continuationKey(ChallengeRepositoryKeys.userPk(userId), sortKey);
    }

    private Set<String> visibleActiveChallengeIds(
            Collection<Challenge> challenges,
            String viewerUserId
    ) {
        if (viewerUserId == null || challenges == null || challenges.isEmpty()) {
            return Set.of();
        }
        Set<String> ownerIds = challenges.stream()
                .flatMap(challenge -> java.util.stream.Stream.of(
                        challenge.getCreatorId(), challenge.getCurrentOwnerId()
                ))
                .filter(java.util.Objects::nonNull)
                .filter(ownerId -> !viewerUserId.equals(ownerId))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> blockedOwnerIds = userBlockRepository.findActiveBlockedCounterpartIds(
                viewerUserId,
                ownerIds
        );
        Set<String> privateChallengeIds = challenges.stream()
                .filter(challenge -> Boolean.TRUE.equals(challenge.getActive()))
                .filter(challenge -> Boolean.TRUE.equals(challenge.getIsPrivate()))
                .filter(challenge -> !viewerUserId.equals(challenge.getCreatorId()))
                .filter(challenge -> !viewerUserId.equals(challenge.getCurrentOwnerId()))
                .map(Challenge::getId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> privateMemberships = userChallengeRepository.findActiveMembershipChallengeIds(
                viewerUserId,
                privateChallengeIds
        );
        return challenges.stream()
                .filter(challenge -> Boolean.TRUE.equals(challenge.getActive()))
                .filter(challenge -> !isBlockedChallengeOwner(
                        challenge, viewerUserId, blockedOwnerIds
                ))
                .filter(challenge -> !Boolean.TRUE.equals(challenge.getIsPrivate())
                        || viewerUserId.equals(challenge.getCreatorId())
                        || viewerUserId.equals(challenge.getCurrentOwnerId())
                        || privateMemberships.contains(challenge.getId()))
                .map(Challenge::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> blockedChallengeIds(
            Collection<Challenge> challenges,
            String viewerUserId
    ) {
        if (viewerUserId == null || challenges == null || challenges.isEmpty()) {
            return Set.of();
        }
        Set<String> ownerIds = challenges.stream()
                .flatMap(challenge -> java.util.stream.Stream.of(
                        challenge.getCreatorId(), challenge.getCurrentOwnerId()
                ))
                .filter(java.util.Objects::nonNull)
                .filter(ownerId -> !viewerUserId.equals(ownerId))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> blockedOwnerIds = userBlockRepository.findActiveBlockedCounterpartIds(
                viewerUserId,
                ownerIds
        );
        return challenges.stream()
                .filter(challenge -> isBlockedChallengeOwner(
                        challenge, viewerUserId, blockedOwnerIds
                ))
                .map(Challenge::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isBlockedChallengeOwner(
            Challenge challenge,
            String viewerUserId,
            Set<String> blockedOwnerIds
    ) {
        if (challenge == null || viewerUserId == null || blockedOwnerIds == null) {
            return false;
        }
        if (viewerUserId.equals(challenge.getCreatorId())
                || viewerUserId.equals(challenge.getCurrentOwnerId())) {
            return false;
        }
        return blockedOwnerIds.contains(challenge.getCreatorId())
                || blockedOwnerIds.contains(challenge.getCurrentOwnerId());
    }

    private UserChallengeCollectionPage findUserChallengeCollectionPage(
            String userId,
            int maximumRows,
            Map<String, Map<String, AttributeValue>> suppliedState
    ) {
        int boundedRows = Math.max(1, Math.min(maximumRows, 100));
        Map<String, Map<String, AttributeValue>> progress = initialUserCollectionState(
                suppliedState
        );
        List<UserChallengeCollectionBuffer> buffers = new ArrayList<>(3);
        PriorityQueue<UserChallengeCollectionBuffer> heads = new PriorityQueue<>((left, right) -> {
            int entryOrder = compareUserCollectionEntries(left.head(), right.head());
            return entryOrder != 0 ? entryOrder : left.name().compareTo(right.name());
        });
        int fetchedRows = 0;
        int queryCalls = 0;
        for (String stream : List.of(
                USER_STREAM_CREATED, USER_STREAM_OWNED, USER_STREAM_JOINED
        )) {
            Map<String, AttributeValue> streamState = progress.get(stream);
            if (done(streamState)) {
                buffers.add(UserChallengeCollectionBuffer.done(stream));
                continue;
            }
            UserChallengeCollectionFetch fetch = fetchUserChallengeCollectionEntries(
                    userId, stream, boundedRows, streamState
            );
            queryCalls++;
            fetchedRows += fetch.entries().size();
            UserChallengeCollectionBuffer buffer = new UserChallengeCollectionBuffer(
                    stream, fetch.entries(), fetch.continuation()
            );
            buffers.add(buffer);
            if (buffer.empty() && buffer.exhausted()) {
                progress.put(stream, doneState());
            } else if (!buffer.empty()) {
                heads.add(buffer);
            }
        }

        List<UserChallengeCollectionCandidate> candidates = new ArrayList<>(boundedRows);
        int rowsExamined = 0;
        while (rowsExamined < boundedRows) {
            boolean refillBlocked = false;
            for (UserChallengeCollectionBuffer buffer : buffers) {
                while (buffer.empty() && !buffer.exhausted()) {
                    int remainingFetchBudget = MAX_FILTER_CANDIDATES - fetchedRows;
                    if (remainingFetchBudget < 1 || queryCalls >= 100) {
                        refillBlocked = true;
                        break;
                    }
                    UserChallengeCollectionFetch fetch = fetchUserChallengeCollectionEntries(
                            userId,
                            buffer.name(),
                            Math.min(boundedRows, remainingFetchBudget),
                            buffer.fetchContinuation()
                    );
                    queryCalls++;
                    fetchedRows += fetch.entries().size();
                    buffer.refill(fetch.entries(), fetch.continuation());
                }
                if (refillBlocked) {
                    break;
                }
                if (!buffer.empty() && !heads.contains(buffer)) {
                    heads.add(buffer);
                }
            }
            if (refillBlocked || heads.isEmpty()) {
                break;
            }
            UserChallengeCollectionBuffer selected = heads.remove();
            UserChallengeCollectionEntry entry = selected.advance();
            rowsExamined++;
            Map<String, AttributeValue> next = selected.empty() && selected.exhausted()
                    ? doneState()
                    : entry.continuation();
            progress.put(selected.name(), next);
            if (!selected.empty()) {
                heads.add(selected);
            }
            if (entry.emit() && entry.challenge() != null) {
                Map<String, Map<String, AttributeValue>> continuation =
                        immutableShardState(progress);
                candidates.add(new UserChallengeCollectionCandidate(
                        entry.challenge(),
                        continuation,
                        hasRemainingUserCollection(buffers, continuation),
                        rowsExamined
                ));
            }
        }

        Map<String, Map<String, AttributeValue>> continuation = immutableShardState(progress);
        return new UserChallengeCollectionPage(
                candidates,
                continuation,
                hasRemainingUserCollection(buffers, continuation),
                rowsExamined
        );
    }

    private UserChallengeCollectionFetch fetchUserChallengeCollectionEntries(
            String userId,
            String stream,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        if (USER_STREAM_CREATED.equals(stream)) {
            DynamoPage<Challenge> page = challengeRepository.findByCreatorIdPage(
                    userId, limit, exclusiveStartKey
            );
            List<UserChallengeCollectionEntry> entries = page.items().stream()
                    .map(challenge -> new UserChallengeCollectionEntry(
                            challenge,
                            challenge.getCreatedAt(),
                            challenge.getId(),
                            continuationKey(
                                    ChallengeRepositoryKeys.challengeCreatorPk(userId),
                                    ChallengeRepositoryKeys.createdSk(
                                            challenge.getCreatedAt(), challenge.getId()
                                    )
                            ),
                            true
                    ))
                    .toList();
            return new UserChallengeCollectionFetch(entries, page.lastEvaluatedKey());
        }
        if (USER_STREAM_OWNED.equals(stream)) {
            DynamoPage<Challenge> page = challengeRepository.findByCurrentOwnerIdPage(
                    userId, limit, exclusiveStartKey
            );
            List<UserChallengeCollectionEntry> entries = page.items().stream()
                    .map(challenge -> new UserChallengeCollectionEntry(
                            challenge,
                            challenge.getCreatedAt(),
                            challenge.getId(),
                            continuationKey(
                                    ChallengeRepositoryKeys.challengeOwnerPk(userId),
                                    ChallengeRepositoryKeys.createdSk(
                                            challenge.getCreatedAt(), challenge.getId()
                                    )
                            ),
                            !userId.equals(challenge.getCreatorId())
                    ))
                    .toList();
            return new UserChallengeCollectionFetch(entries, page.lastEvaluatedKey());
        }
        if (!USER_STREAM_JOINED.equals(stream)) {
            throw new IllegalArgumentException("Unknown user challenge stream " + stream);
        }
        DynamoPage<UserChallenge> page = userChallengeRepository.findActiveByUserIdPage(
                userId, limit, exclusiveStartKey
        );
        Map<String, Challenge> challengesById = challengeRepository.findAllById(
                        page.items().stream()
                                .map(UserChallenge::getChallengeId)
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(Challenge::getId, challenge -> challenge));
        List<UserChallengeCollectionEntry> entries = page.items().stream()
                .map(membership -> {
                    Challenge challenge = challengesById.get(membership.getChallengeId());
                    boolean emit = challenge != null
                            && !userId.equals(challenge.getCreatorId())
                            && !userId.equals(challenge.getCurrentOwnerId());
                    return new UserChallengeCollectionEntry(
                            challenge,
                            membership.getJoinedAt(),
                            membership.getId(),
                            continuationKey(
                                    ChallengeRepositoryKeys.userPk(userId),
                                    ChallengeRepositoryKeys.membershipStatusSk(
                                            "ACTIVE", membership.getJoinedAt(), membership.getId()
                                    )
                            ),
                            emit
                    );
                })
                .toList();
        return new UserChallengeCollectionFetch(entries, page.lastEvaluatedKey());
    }

    private Map<String, Map<String, AttributeValue>> decodeUserCollectionContinuation(
            String cursor,
            String queryBinding,
            String userId
    ) throws ChallengeSphereException {
        Map<String, Map<String, AttributeValue>> state = decodeShardContinuation(
                cursor, queryBinding
        );
        if (state.isEmpty()) {
            return Map.of();
        }
        try {
            if (!state.keySet().equals(Set.of(
                    USER_STREAM_CREATED, USER_STREAM_OWNED, USER_STREAM_JOINED
            ))) {
                throw new IllegalArgumentException("Unexpected user challenge cursor streams");
            }
            validateStreamState(
                    state.get(USER_STREAM_CREATED),
                    ChallengeRepositoryKeys.challengeCreatorPk(userId),
                    ChallengeRepositoryKeys.CREATED_PREFIX
            );
            validateStreamState(
                    state.get(USER_STREAM_OWNED),
                    ChallengeRepositoryKeys.challengeOwnerPk(userId),
                    ChallengeRepositoryKeys.CREATED_PREFIX
            );
            validateStreamState(
                    state.get(USER_STREAM_JOINED),
                    ChallengeRepositoryKeys.userPk(userId),
                    ChallengeRepositoryKeys.membershipStatusPrefix("ACTIVE") + '#'
            );
            return state;
        } catch (IllegalArgumentException invalidState) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private Map<String, Map<String, AttributeValue>> decodeShardContinuation(
            String cursor,
            String queryBinding
    ) throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            DynamoDbCursorCodec.CursorState state = cursorCodec.decode(cursor, queryBinding);
            if (!state.lastEvaluatedKey().isEmpty()) {
                throw new IllegalArgumentException("Cursor has unexpected single-partition state");
            }
            return state.shardState();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeShardContinuation(
            String queryBinding,
            Map<String, Map<String, AttributeValue>> continuation,
            boolean hasMore
    ) {
        if (!hasMore || continuation == null || continuation.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(Map.of(), continuation)
        );
    }

    private static Map<String, Map<String, AttributeValue>> initialUserCollectionState(
            Map<String, Map<String, AttributeValue>> suppliedState
    ) {
        Map<String, Map<String, AttributeValue>> state = new LinkedHashMap<>();
        state.put(
                USER_STREAM_CREATED,
                suppliedState == null
                        ? Map.of()
                        : suppliedState.getOrDefault(USER_STREAM_CREATED, Map.of())
        );
        state.put(
                USER_STREAM_OWNED,
                suppliedState == null
                        ? Map.of()
                        : suppliedState.getOrDefault(USER_STREAM_OWNED, Map.of())
        );
        state.put(
                USER_STREAM_JOINED,
                suppliedState == null
                        ? Map.of()
                        : suppliedState.getOrDefault(USER_STREAM_JOINED, Map.of())
        );
        return state;
    }

    private static void validateStreamState(
            Map<String, AttributeValue> state,
            String partitionKey,
            String sortKeyPrefix
    ) {
        if (state == null || state.isEmpty() || done(state)) {
            return;
        }
        AttributeValue partition = state.get(DynamoDbKeys.PARTITION_KEY);
        AttributeValue sort = state.get(DynamoDbKeys.SORT_KEY);
        if (partition == null
                || !partitionKey.equals(partition.s())
                || sort == null
                || sort.s() == null
                || !sort.s().startsWith(sortKeyPrefix)) {
            throw new IllegalArgumentException("Cursor stream key is invalid");
        }
    }

    private static int compareUserCollectionEntries(
            UserChallengeCollectionEntry left,
            UserChallengeCollectionEntry right
    ) {
        return compareCreatedAtDesc(
                left.activityAt(), left.tieBreaker(),
                right.activityAt(), right.tieBreaker()
        );
    }

    private static Map<String, AttributeValue> continuationKey(
            String partitionKey,
            String sortKey
    ) {
        return Map.of(
                DynamoDbKeys.PARTITION_KEY, AttributeValue.fromS(partitionKey),
                DynamoDbKeys.SORT_KEY, AttributeValue.fromS(sortKey)
        );
    }

    private static boolean hasRemainingUserCollection(
            Collection<UserChallengeCollectionBuffer> buffers,
            Map<String, Map<String, AttributeValue>> state
    ) {
        return buffers.stream().anyMatch(UserChallengeCollectionBuffer::hasRemaining)
                || state.values().stream().anyMatch(value -> !done(value));
    }

    private static boolean done(Map<String, AttributeValue> state) {
        AttributeValue marker = state == null ? null : state.get(CURSOR_DONE);
        return marker != null && "1".equals(marker.s());
    }

    private static Map<String, AttributeValue> doneState() {
        return Map.of(CURSOR_DONE, AttributeValue.fromS("1"));
    }

    private static Map<String, Map<String, AttributeValue>> immutableShardState(
            Map<String, Map<String, AttributeValue>> state
    ) {
        Map<String, Map<String, AttributeValue>> copy = new LinkedHashMap<>();
        state.forEach((name, key) -> copy.put(
                name,
                key == null || key.isEmpty() ? Map.of() : Map.copyOf(key)
        ));
        return Map.copyOf(copy);
    }

    private record UserChallengeCollectionEntry(
            Challenge challenge,
            Timestamp activityAt,
            String tieBreaker,
            Map<String, AttributeValue> continuation,
            boolean emit
    ) {
    }

    private record UserChallengeCollectionFetch(
            List<UserChallengeCollectionEntry> entries,
            Map<String, AttributeValue> continuation
    ) {
    }

    private record UserChallengeCollectionCandidate(
            Challenge challenge,
            Map<String, Map<String, AttributeValue>> continuation,
            boolean hasMore,
            int rowsExaminedThrough
    ) {
    }

    private record UserChallengeCollectionPage(
            List<UserChallengeCollectionCandidate> candidates,
            Map<String, Map<String, AttributeValue>> continuation,
            boolean hasMore,
            int rowsExamined
    ) {
    }

    private static final class UserChallengeCollectionBuffer {
        private final String name;
        private final ArrayDeque<UserChallengeCollectionEntry> entries = new ArrayDeque<>();
        private Map<String, AttributeValue> fetchContinuation;
        private boolean exhausted;

        private UserChallengeCollectionBuffer(
                String name,
                List<UserChallengeCollectionEntry> entries,
                Map<String, AttributeValue> pageContinuation
        ) {
            this.name = name;
            if (entries != null) {
                this.entries.addAll(entries);
            }
            this.fetchContinuation = pageContinuation == null
                    ? Map.of()
                    : Map.copyOf(pageContinuation);
            this.exhausted = this.fetchContinuation.isEmpty();
        }

        private static UserChallengeCollectionBuffer done(String name) {
            return new UserChallengeCollectionBuffer(name, List.of(), Map.of());
        }

        private String name() {
            return name;
        }

        private UserChallengeCollectionEntry head() {
            return entries.getFirst();
        }

        private UserChallengeCollectionEntry advance() {
            return entries.removeFirst();
        }

        private boolean exhausted() {
            return exhausted;
        }

        private boolean empty() {
            return entries.isEmpty();
        }

        private boolean hasRemaining() {
            return !entries.isEmpty() || !exhausted;
        }

        private Map<String, AttributeValue> fetchContinuation() {
            return fetchContinuation;
        }

        private void refill(
                List<UserChallengeCollectionEntry> fetched,
                Map<String, AttributeValue> nextFetchContinuation
        ) {
            if (!entries.isEmpty()) {
                throw new IllegalStateException(
                        "User challenge collection buffer must be empty before refill"
                );
            }
            if (fetched != null) {
                entries.addAll(fetched);
            }
            fetchContinuation = nextFetchContinuation == null
                    ? Map.of()
                    : Map.copyOf(nextFetchContinuation);
            exhausted = fetchContinuation.isEmpty();
        }
    }

    private ChallengeDiscoveryPage findPublicDiscoveryPage(
            String currentUserId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<Challenge> visible = new ArrayList<>(limit);
        Map<String, AttributeValue> continuation = exclusiveStartKey == null
                ? Map.of()
                : Map.copyOf(exclusiveStartKey);
        int pageReads = 0;
        while (visible.size() < limit && pageReads++ < MAX_FILTER_PAGE_READS) {
            DynamoPage<Challenge> page = challengeRepository.findPublicActivePage(
                    limit - visible.size(),
                    continuation
            );
            page.items().stream()
                    .filter(challenge -> Boolean.TRUE.equals(challenge.getActive()))
                    .filter(challenge -> !Boolean.TRUE.equals(challenge.getIsPrivate()))
                    .filter(challenge -> !isExpiredChallenge(challenge))
                    .filter(challenge -> canViewChallengeDiscovery(challenge, currentUserId))
                    .filter(challenge -> withinStartDateRange(challenge, startDate, endDate))
                    .limit(limit - visible.size())
                    .forEach(visible::add);
            continuation = page.lastEvaluatedKey();
            if (continuation.isEmpty() || page.items().isEmpty()) {
                break;
            }
        }
        return new ChallengeDiscoveryPage(
                List.copyOf(visible),
                continuation,
                !continuation.isEmpty()
        );
    }

    private static boolean withinStartDateRange(
            Challenge challenge,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (startDate == null && endDate == null) {
            return true;
        }
        LocalDate challengeStartDate = challenge.getStartDate();
        return challengeStartDate != null
                && (startDate == null || !challengeStartDate.isBefore(startDate))
                && (endDate == null || !challengeStartDate.isAfter(endDate));
    }

    private Map<String, AttributeValue> decodeContinuation(
            String cursor,
            String queryBinding
    ) throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            DynamoDbCursorCodec.CursorState state = cursorCodec.decode(cursor, queryBinding);
            if (!state.shardState().isEmpty()) {
                throw new IllegalArgumentException("Cursor has unexpected shard state");
            }
            return state.lastEvaluatedKey();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeContinuation(
            String queryBinding,
            Map<String, AttributeValue> continuation
    ) {
        if (continuation == null || continuation.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(continuation)
        );
    }

    private record ChallengeDiscoveryPage(
            List<Challenge> challenges,
            Map<String, AttributeValue> continuation,
            boolean hasMore
    ) {
    }

    private void ensureFollowAccess(String ownerUserId, String viewerUserId, String contentType)
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

    private boolean canViewChallengeDiscovery(Challenge challenge, String currentUserId) {
        return canViewChallenge(challenge, currentUserId);
    }

    private boolean canKeepFavoriteChallenge(
            Challenge challenge,
            UserChallenge membership,
            String userId
    ) {
        if (challenge == null
                || membership == null
                || userId == null
                || Boolean.TRUE.equals(membership.getBanned())) {
            return false;
        }
        if (Boolean.TRUE.equals(membership.getActive())) {
            return true;
        }
        if (userId.equals(challenge.getCreatorId())
                || userId.equals(challenge.getCurrentOwnerId())) {
            return true;
        }
        if (Boolean.FALSE.equals(challenge.getActive())) {
            return !Boolean.TRUE.equals(challenge.getIsPrivate())
                    || Boolean.TRUE.equals(membership.getFavorite());
        }
        return !Boolean.TRUE.equals(challenge.getIsPrivate());
    }

    private boolean canViewInactiveChallengeHistory(
            Challenge challenge,
            UserChallenge membership,
            String userId
    ) {
        if (challenge == null
                || membership == null
                || userId == null
                || Boolean.TRUE.equals(membership.getBanned())) {
            return false;
        }
        boolean inactiveRelationship = !Boolean.TRUE.equals(challenge.getActive())
                || !Boolean.TRUE.equals(membership.getActive());
        if (!inactiveRelationship) {
            return false;
        }
        if (userId.equals(challenge.getCreatorId())
                || userId.equals(challenge.getCurrentOwnerId())) {
            return true;
        }
        return !Boolean.TRUE.equals(challenge.getIsPrivate());
    }

    private boolean canViewArchivedChallenge(
            Challenge challenge,
            UserChallenge membership,
            String userId
    ) {
        return canViewInactiveChallengeHistory(challenge, membership, userId)
                || (membership != null
                        && Boolean.TRUE.equals(membership.getFavorite())
                        && canKeepFavoriteChallenge(challenge, membership, userId));
    }

    private boolean canViewChallenge(Challenge challenge, String currentUserId) {
        if (challenge == null || currentUserId == null || !Boolean.TRUE.equals(challenge.getActive())) {
            return false;
        }
        String ownerUserId = challenge.getCurrentOwnerId() != null ? challenge.getCurrentOwnerId() : challenge.getCreatorId();
        if (currentUserId.equals(ownerUserId) || currentUserId.equals(challenge.getCreatorId())) {
            return true;
        }
        if (isBlockedChallengeOwner(challenge, currentUserId)) {
            return false;
        }
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            return true;
        }
        return userChallengeRepository.findByUserIdAndChallengeId(currentUserId, challenge.getId())
                .map(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive())
                        && !Boolean.TRUE.equals(userChallenge.getBanned()))
                .orElse(false);
    }

    private boolean canJoinChallenge(
            Challenge challenge,
            String ownerUserId,
            String userId,
            boolean verifiedInvitation) {
        if (challenge == null || userId == null) {
            return false;
        }
        if (userId.equals(ownerUserId) || userId.equals(challenge.getCreatorId())) {
            return true;
        }
        if (isBlockedChallengeOwner(challenge, userId)) {
            return false;
        }
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            return true;
        }
        return verifiedInvitation;
    }

    private boolean isBlockedChallengeOwner(Challenge challenge, String viewerUserId) {
        if (challenge == null || viewerUserId == null) {
            return false;
        }
        String creatorUserId = challenge.getCreatorId();
        String currentOwnerUserId = challenge.getCurrentOwnerId();
        if (viewerUserId.equals(creatorUserId) || viewerUserId.equals(currentOwnerUserId)) {
            return false;
        }
        return (creatorUserId != null
                && !creatorUserId.equals(viewerUserId)
                && userBlockRepository.existsActiveBlockBetween(viewerUserId, creatorUserId))
                || (currentOwnerUserId != null
                && !currentOwnerUserId.equals(viewerUserId)
                && !currentOwnerUserId.equals(creatorUserId)
                && userBlockRepository.existsActiveBlockBetween(viewerUserId, currentOwnerUserId));
    }

    private void notifyChallengeOwnersOfJoins(Challenge challenge, List<String> successfulUserIds) {
        Set<String> recipientUserIds = new HashSet<>();
        if (challenge.getCreatorId() != null) {
            recipientUserIds.add(challenge.getCreatorId());
        }
        if (challenge.getCurrentOwnerId() != null) {
            recipientUserIds.add(challenge.getCurrentOwnerId());
        }
        if (recipientUserIds.isEmpty()) {
            return;
        }

        Set<String> recipients = Set.copyOf(recipientUserIds);
        List<String> joinedUsers = List.copyOf(successfulUserIds);
        runAfterCommit(() -> {
            for (String joinedUserId : joinedUsers) {
                try {
                    notificationService.notifyChallengeJoinedAsync(challenge, joinedUserId, recipients);
                } catch (Exception e) {
                    logger.warn("Failed to notify challenge owner(s) for challengeId={} joinedUserId={}: {}",
                            challenge.getId(), joinedUserId, e.getClass().getSimpleName());
                }
            }
        });
    }

    private void notifyChallengeInvitesAfterCommit(
            Challenge challenge,
            String actorUserId,
            Collection<String> requestedRecipientUserIds
    ) {
        if (requestedRecipientUserIds == null || requestedRecipientUserIds.isEmpty()) {
            return;
        }
        List<String> recipients = new ArrayList<>(requestedRecipientUserIds);
        runAfterCommit(() -> {
            try {
                notificationService.notifyChallengeInvitesAsync(challenge, actorUserId, recipients);
            } catch (Exception exception) {
                logger.warn("Failed to schedule challenge invite notifications for challengeId={}: {}",
                        challenge == null ? null : challenge.getId(),
                        exception.getClass().getSimpleName());
            }
        });
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }
}
