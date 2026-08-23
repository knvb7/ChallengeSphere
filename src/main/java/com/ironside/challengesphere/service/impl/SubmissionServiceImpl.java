package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.ironside.challengesphere.util.GetSignedUrlForS3;
import com.ironside.challengesphere.util.MediaTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.ChallengeSubmissionPreviewResponse;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SubmissionPreviewItemResponse;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.projection.SubmissionPreviewProjection;
import com.ironside.challengesphere.security.IssuedUploadService;
import com.ironside.challengesphere.security.UploadQuotaService;
import com.ironside.challengesphere.service.ChallengeCrewService;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.SubmissionService;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class SubmissionServiceImpl implements SubmissionService {
    private static final Logger logger = LogManager.getLogger(SubmissionServiceImpl.class);
    private static final int SUBMISSION_GRACE_DAYS = 1;
    private static final int MAX_SUBMISSION_MEDIA_FILES = 5;
    private static final int MAX_LIMIT = 100;
    private static final long MAX_BACKEND_UPLOAD_BYTES = 8L * 1024L * 1024L;
    private final Clock clock;

    public SubmissionServiceImpl() {
        this(Clock.systemUTC());
    }

    SubmissionServiceImpl(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Autowired
    private SubmissionRepository submissionRepository;
    
    @Autowired
    private ChallengeRepository challengeRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserChallengeRepository userChallengeRepository;
    
    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ChallengeCrewService challengeCrewService;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private SubmissionMediaKeyPolicy submissionMediaKeyPolicy;

    @Autowired
    private UploadQuotaService uploadQuotaService;

    @Autowired
    private IssuedUploadService issuedUploadService;

    @Autowired
    private ContentModerationService contentModerationService;

    @Autowired
    private ModerationEnforcementService moderationEnforcementService;

    @Autowired
    private FeedTimelineMaterializer feedTimelineMaterializer;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException {
        String userId = request.getUserId();
        String challengeId = request.getChallengeId();
        Integer dayNumber = request.getDayNumber();
        List<String> mediaKeys = submissionMediaKeyPolicy.validateAndNormalize(userId, request.getMediaUrls());
        requireSubmissionMedia(mediaKeys);

        logger.info("Creating submission for user: {}, challenge: {}, day: {}", userId, challengeId, dayNumber);

        try {
            // Validate user exists
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "User not found with id: " + userId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            if (Boolean.FALSE.equals(user.getActive())) {
                throw new ChallengeSphereException(
                        "Account is suspended — contact support.",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            // Validate challenge exists and is active
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            if (!Boolean.TRUE.equals(challenge.getActive())) {
                throw new ChallengeSphereException(
                        "Cannot submit to an inactive challenge",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            requireActiveChallengeMembership(userId, challengeId);
            requireChallengeStarted(challengeId);
            validateSubmissionProof(request.getDescription(), mediaKeys);

            boolean oneOffChallenge = isOneOffChallenge(challenge);
            if (oneOffChallenge) {
                dayNumber = normalizeOneOffSubmissionDay(dayNumber);
            } else {
                // Validate day number doesn't exceed challenge duration
                if (dayNumber == null || dayNumber < 1) {
                    throw new ChallengeSphereException(
                            "Day number must be greater than zero",
                            HttpStatus.BAD_REQUEST,
                            logger
                    );
                }
                if (challenge.getNumberOfDays() != null && dayNumber > challenge.getNumberOfDays()) {
                    throw new ChallengeSphereException(
                            "Day number " + dayNumber + " exceeds challenge duration of " + challenge.getNumberOfDays() + " days",
                            HttpStatus.BAD_REQUEST,
                            logger
                    );
                }
            }
            ensureSubmissionDayAcceptsUpload(challenge, dayNumber, request.getTimezone());
            LocalDate submissionLocalDate = getClientLocalDate(request.getTimezone());
            boolean requestedPrivate = Boolean.TRUE.equals(challenge.getIsPrivate())
                    || Boolean.TRUE.equals(request.getIsPrivate());

            // Give an immediate, descriptive response when the cap is already
            // reached. The repository repeats this guard atomically at write time.
            List<Submission> existingSubmissions = submissionRepository.findByUserChallengeAndDay(userId, challengeId, dayNumber);
            List<Submission> activeSubmissions = existingSubmissions.stream()
                    .filter(submission -> Boolean.TRUE.equals(submission.getActive()))
                    .toList();
            if (oneOffChallenge && !activeSubmissions.isEmpty()) {
                return reconcileExistingOneOffSubmission(
                        activeSubmissions.get(0),
                        request,
                        mediaKeys,
                        requestedPrivate,
                        challenge
                );
            }
            if (!oneOffChallenge && activeSubmissions.size() >= 5) {
                logger.warn("Submission limit reached for user: {}, challenge: {}, day: {}", userId, challengeId, dayNumber);
                throw new ChallengeSphereException(
                        "You have reached the maximum of 5 submissions for day " + dayNumber + ".",
                        HttpStatus.TOO_MANY_REQUESTS,
                        logger
                );
            }

            issuedUploadService.consume(
                    userId, mediaKeys, IssuedUploadService.Purpose.SUBMISSION_PHOTO);
            moderateSubmissionMediaOrReject(userId, mediaKeys);

            // Create submission
            Submission submission = new Submission();
            submission.setUserId(userId);
            submission.setChallengeId(challengeId);
            submission.setDayNumber(dayNumber);
            submission.setDescription(request.getDescription());

            // Convert media URLs list to comma-separated string
            submission.setMediaUrls(mediaUrlService.toStoredMediaUrlsString(mediaKeys));

            submission.setIsPrivate(requestedPrivate);

            // Submission time is server-authoritative; clients cannot backdate or future-date activity.
            Timestamp submissionTime = Timestamp.from(Instant.now());
            submission.setSubmissionTime(submissionTime);
            submission.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            submission.setActive(true);
            submission.setModerationStatus(Submission.MODERATION_PASSED);
            submission.setModerationStartedAt(null);
            submission.setModeratedAt(new Timestamp(System.currentTimeMillis()));
            submission.setModerationError(null);
            submission.setGloriesCount(0L);
            submission.setCommentsCount(0L);
            submission.setVersion(0);

            Submission savedSubmission;
            try {
                savedSubmission = submissionRepository.saveWithinDayLimit(
                        submission,
                        oneOffChallenge ? 1 : 5
                );
            } catch (SubmissionRepository.SubmissionDayLimitExceededException limitReached) {
                if (oneOffChallenge) {
                    List<Submission> concurrentSubmissions = submissionRepository
                            .findByUserChallengeAndDay(userId, challengeId, dayNumber)
                            .stream()
                            .filter(candidate -> Boolean.TRUE.equals(candidate.getActive()))
                            .toList();
                    if (!concurrentSubmissions.isEmpty()) {
                        return reconcileExistingOneOffSubmission(
                                concurrentSubmissions.get(0),
                                request,
                                mediaKeys,
                                requestedPrivate,
                                challenge
                        );
                    }
                }
                throw new ChallengeSphereException(
                        oneOffChallenge
                                ? "This one-off challenge already has an active submission."
                                : "You have reached the maximum of 5 submissions for day " + dayNumber + ".",
                        oneOffChallenge ? HttpStatus.BAD_REQUEST : HttpStatus.TOO_MANY_REQUESTS,
                        logger
                );
            }

            logger.info("Successfully created submission with id: {} for user: {}, challenge: {}, day: {}",
                    savedSubmission.getId(), userId, challengeId, dayNumber);
            finishPersistedSubmission(
                    savedSubmission, challenge, oneOffChallenge, submissionLocalDate
            );

            return SubmissionResponse.success(savedSubmission, mediaKeys);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error creating submission for user: {}, challenge: {}: {}", userId, challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to create submission",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getSubmissionsByUserAndChallenge(
            String userId,
            String challengeId,
            String callerId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 10);
        String queryBinding = cursorBinding(
                "submission:user-challenge:v2",
                userId,
                challengeId,
                callerId,
                boundedLimit
        );
        logger.info("Getting submissions for user: {}, challenge: {}, limit: {}",
                userId, challengeId, boundedLimit);

        try {
            Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor, queryBinding);
            DynamoPage<Submission> submissionPage =
                    submissionRepository.findActiveByUserAndChallengePage(
                            userId,
                            challengeId,
                            callerId,
                            boundedLimit,
                            exclusiveStartKey
                    );
            boolean hasMore = submissionPage.hasMore();

            logger.info("Found {} visible submissions for user: {}, challenge: {}",
                    submissionPage.items().size(), userId, challengeId);
            return SubmissionsResponse.create(
                    withReadableMediaUrls(submissionPage.items()),
                    encodeCursor(queryBinding, submissionPage.lastEvaluatedKey()),
                    hasMore
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting submissions for user: {}, challenge: {}: {}", userId, challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getRandomChallengeSubmissionsByDay(
            String challengeId,
            Integer dayNumber,
            String callerId,
            int limit,
            String cursor,
            String sortBy,
            String participantQuery
    ) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 20);
        String normalizedSort = normalizeGlobalSubmissionSort(sortBy);
        String normalizedParticipantQuery = normalizeParticipantQuery(participantQuery);
        String queryBinding = cursorBinding(
                "submission:challenge-day:v2",
                challengeId,
                dayNumber,
                callerId,
                normalizedSort,
                normalizedParticipantQuery,
                boundedLimit
        );
        logger.info("Getting global challenge submissions: challenge={}, day={}, limit={}, sortBy={}",
                challengeId, dayNumber, boundedLimit, normalizedSort);

        if (dayNumber == null || dayNumber < 1) {
            throw new ChallengeSphereException(
                    "Day number must be greater than zero",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        try {
            Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor, queryBinding);
            DynamoPage<Submission> submissionPage = submissionRepository.findVisibleByChallengePage(
                    challengeId,
                    dayNumber,
                    callerId,
                    normalizedSort,
                    normalizedParticipantQuery,
                    boundedLimit,
                    exclusiveStartKey
            );

            logger.info("Found {} visible submissions for challenge: {}, day: {}",
                    submissionPage.items().size(), challengeId, dayNumber);
            boolean hasMore = submissionPage.hasMore();
            return SubmissionsResponse.create(
                    withReadableMediaUrls(submissionPage.items()),
                    encodeCursor(queryBinding, submissionPage.lastEvaluatedKey()),
                    hasMore
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting random submissions for challenge {}, day {}: {}", challengeId, dayNumber, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmissionResponse reuploadSubmission(String submissionId, CreateSubmissionRequest request) throws ChallengeSphereException {
        if (submissionId == null) {
            throw new ChallengeSphereException("Submission ID is required", HttpStatus.BAD_REQUEST, logger);
        }
        String userId = request.getUserId();
        List<String> mediaKeys = submissionMediaKeyPolicy.validateAndNormalize(userId, request.getMediaUrls());
        requireSubmissionMedia(mediaKeys);
        logger.info("Reuploading submission: {} for user: {}", submissionId, userId);

        try {
            Submission submission = submissionRepository.findById(submissionId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Submission not found with id: " + submissionId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            ensureSubmissionOwner(submission, userId);
            if (!Boolean.TRUE.equals(submission.getActive())) {
                throw new ChallengeSphereException(
                        "Cannot reupload a deleted submission",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            Challenge challenge = challengeRepository.findById(submission.getChallengeId())
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + submission.getChallengeId(),
                            HttpStatus.NOT_FOUND,
                            logger
                    ));
            requireActiveChallengeMembership(userId, submission.getChallengeId());
            requireChallengeStarted(submission.getChallengeId());
            validateSubmissionProof(request.getDescription(), mediaKeys);
            ensureSubmissionDayAcceptsUpload(challenge, submission.getDayNumber(), request.getTimezone());
            LocalDate submissionLocalDate = getClientLocalDate(request.getTimezone());

            String storedMediaKeys = mediaUrlService.toStoredMediaUrlsString(mediaKeys);
            Set<String> existingMediaKeys = submission.getMediaUrls() == null
                    ? Set.of()
                    : new HashSet<>(List.of(submission.getMediaUrls().split(",")));
            List<String> newlyAttachedMediaKeys = mediaKeys.stream()
                    .filter(key -> !existingMediaKeys.contains(key))
                    .toList();
            if (!newlyAttachedMediaKeys.isEmpty()) {
                issuedUploadService.consume(
                        userId,
                        newlyAttachedMediaKeys,
                        IssuedUploadService.Purpose.SUBMISSION_PHOTO);
                moderateSubmissionMediaOrReject(userId, newlyAttachedMediaKeys);
            }
            Boolean requestedPrivacy = Boolean.TRUE.equals(challenge.getIsPrivate())
                    ? true
                    : request.getIsPrivate();
            boolean mediaChanged = !newlyAttachedMediaKeys.isEmpty();
            Submission savedSubmission = submissionRepository.mutateExisting(submissionId, latest -> {
                latest.setDescription(request.getDescription());
                latest.setMediaUrls(storedMediaKeys);
                if (requestedPrivacy != null) {
                    // A submission can never be made public while its parent challenge is private.
                    latest.setIsPrivate(requestedPrivacy);
                }
                Timestamp updatedAt = Timestamp.from(Instant.now());
                latest.setSubmissionTime(updatedAt);
                latest.setUpdatedAt(updatedAt);
                if (mediaChanged) {
                    latest.setModerationStatus(Submission.MODERATION_PASSED);
                    latest.setModerationStartedAt(null);
                    latest.setModeratedAt(updatedAt);
                    latest.setModerationError(null);
                }
            });
            invalidateSubmissionCaches(savedSubmission);
            boolean leaderboardRebuilt = false;
            try {
                leaderboardService.rebuildLeaderboard(savedSubmission.getChallengeId());
                leaderboardRebuilt = true;
            } catch (Exception e) {
                logger.warn("Failed to rebuild leaderboard after reuploading submission {}: {}",
                        submissionId, e.getClass().getSimpleName());
            }
            if (Boolean.TRUE.equals(savedSubmission.getActive()) && !isOneOffChallenge(challenge)) {
                notifyMomentumMilestonesAfterCommit(
                        userId,
                        savedSubmission.getChallengeId(),
                        submissionLocalDate,
                        leaderboardRebuilt
                );
            }
            logger.info("Successfully reuploaded submission: {} for user: {}", submissionId, userId);
            return SubmissionResponse.success(savedSubmission, mediaKeys);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error reuploading submission: {} for user: {}: {}", submissionId, userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to reupload submission", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    @Override
    @Transactional
    public void deleteSubmission(String submissionId, String userId) throws ChallengeSphereException {
        if (submissionId == null) {
            throw new ChallengeSphereException("Submission ID is required", HttpStatus.BAD_REQUEST, logger);
        }
        logger.info("Deleting submission: {} for user: {}", submissionId, userId);

        try {
            Submission submission = submissionRepository.findById(submissionId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Submission not found with id: " + submissionId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            ensureSubmissionOwner(submission, userId);
            if (!Boolean.TRUE.equals(submission.getActive())) {
                logger.info("Submission {} is already inactive", submissionId);
                return;
            }

            boolean shouldRemoveSubmissionPoints = submission.getDayNumber() != null
                    && submissionRepository.countActiveByUserIdAndChallengeIdAndDayNumber(
                            userId,
                            submission.getChallengeId(),
                            submission.getDayNumber()) <= 1;

            Submission deletedSubmission = submissionRepository.mutateExisting(submissionId, latest -> {
                latest.setActive(false);
                latest.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            });
            invalidateSubmissionCaches(deletedSubmission);
            if (shouldRemoveSubmissionPoints) {
                leaderboardService.decrementSubmissionScore(
                        submission.getChallengeId(),
                        userId,
                        10,
                        "Deleted submission for day " + submission.getDayNumber());
            }
            try {
                leaderboardService.rebuildLeaderboard(submission.getChallengeId());
            } catch (Exception e) {
                logger.warn("Failed to rebuild leaderboard after deleting submission {}: {}", submissionId, e.getClass().getSimpleName());
            }
            logger.info("Successfully deleted submission: {} for user: {}", submissionId, userId);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error deleting submission: {} for user: {}: {}", submissionId, userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException("Failed to delete submission", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getSubmissionsByChallenge(
            String challengeId,
            String callerId,
            int limit,
            String cursor,
            String sortBy,
            String participantQuery
    ) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 10);
        String normalizedSort = normalizeGlobalSubmissionSort(sortBy);
        String normalizedParticipantQuery = normalizeParticipantQuery(participantQuery);
        String queryBinding = cursorBinding(
                "submission:challenge:v2",
                challengeId,
                callerId,
                normalizedSort,
                normalizedParticipantQuery,
                boundedLimit
        );
        logger.info("Getting submissions for challenge: {}, limit: {}, sortBy: {}",
                challengeId, boundedLimit, normalizedSort);

        try {
            Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor, queryBinding);
            DynamoPage<Submission> submissionPage = submissionRepository.findVisibleByChallengePage(
                    challengeId,
                    null,
                    callerId,
                    normalizedSort,
                    normalizedParticipantQuery,
                    boundedLimit,
                    exclusiveStartKey
            );

            logger.info("Found {} active submissions for challenge: {}",
                    submissionPage.items().size(), challengeId);
            boolean hasMore = submissionPage.hasMore();
            return SubmissionsResponse.create(
                    withReadableMediaUrls(submissionPage.items()),
                    encodeCursor(queryBinding, submissionPage.lastEvaluatedKey()),
                    hasMore
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting submissions for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChallengeSubmissionPreviewResponse> getSubmissionPreviews(
            List<String> challengeIds,
            String userId,
            String callerId) throws ChallengeSphereException {
        List<String> ids = challengeIds == null ? List.of() : challengeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .limit(50)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        if (userId != null) {
            ensureFollowAccess(userId, callerId, "submissions");
        }

        Map<String, List<SubmissionPreviewItemResponse>> items = new LinkedHashMap<>();
        Map<String, Long> totals = new HashMap<>();
        Map<String, Boolean> totalCountExact = new HashMap<>();
        Map<String, Boolean> hasMore = new HashMap<>();
        ids.forEach(id -> items.put(id, new ArrayList<>()));

        List<SubmissionPreviewProjection> previewRows =
                submissionRepository.findSubmissionPreviews(ids, userId, callerId);
        Set<String> previewOwnerUserIds = previewRows.stream()
                .map(SubmissionPreviewProjection::getUserId)
                .filter(SubmissionServiceImpl::hasText)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, User> previewOwnersById = hydrateUsersById(previewOwnerUserIds);

        for (SubmissionPreviewProjection row : previewRows) {
            List<SubmissionPreviewItemResponse> challengeItems = items.get(row.getChallengeId());
            totals.put(row.getChallengeId(), row.getTotalCount());
            totalCountExact.put(row.getChallengeId(), Boolean.TRUE.equals(row.getTotalCountExact()));
            hasMore.put(row.getChallengeId(), Boolean.TRUE.equals(row.getHasMore()));
            if (!hasText(row.getSubmissionId())) {
                continue;
            }
            if (challengeItems == null || challengeItems.size() >= 4) {
                continue;
            }
            List<String> safeMediaKeys = submissionMediaKeyPolicy.filterOwnedKeysForRead(
                    row.getMediaUrls(),
                    submissionMediaOwnerIds(
                            row.getUserId(),
                            previewOwnersById.get(row.getUserId())
                    ));
            String firstMedia = mediaUrlService.toReadableMediaUrlList(String.join(",", safeMediaKeys))
                    .stream()
                    .filter(this::isImageMediaUrl)
                    .findFirst()
                    .orElse(null);
            if (firstMedia == null) {
                continue;
            }
            challengeItems.add(new SubmissionPreviewItemResponse(
                    row.getSubmissionId(),
                    row.getUserId(),
                    firstMedia,
                    Timestamp.from(row.getSubmissionTime())
            ));
        }

        return ids.stream()
                .map(id -> new ChallengeSubmissionPreviewResponse(
                        id,
                        List.copyOf(items.get(id)),
                        totals.getOrDefault(id, 0L),
                        totalCountExact.getOrDefault(id, true),
                        hasMore.getOrDefault(id, false)
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getSubmissionsByUser(
            String userId,
            String callerId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        int boundedLimit = normalizeLimit(limit, 10);
        String queryBinding = cursorBinding(
                "submission:user:v2",
                userId,
                callerId,
                boundedLimit
        );
        logger.info("Getting submissions for user: {}, limit: {}", userId, boundedLimit);

        try {
            ensureFollowAccess(userId, callerId, "posts");
            Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor, queryBinding);
            DynamoPage<Submission> submissionPage = submissionRepository.findActiveByUserPage(
                    userId,
                    callerId,
                    !userId.equals(callerId),
                    boundedLimit,
                    exclusiveStartKey
            );
            boolean hasMore = submissionPage.hasMore();

            logger.info("Found {} visible submissions for user: {}",
                    submissionPage.items().size(), userId);
            return SubmissionsResponse.create(
                    withReadableMediaUrls(submissionPage.items()),
                    encodeCursor(queryBinding, submissionPage.lastEvaluatedKey()),
                    hasMore
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting submissions for user {}: {}", userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public PresignedUrlsResponse generatePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException {
        String userId = request.getUserId();
        List<GeneratePresignedUrlsRequest.MediaFileInfo> mediaFiles = request.getMediaFiles();

        validateSubmissionPresignRequest(mediaFiles);
        logger.info("Generating presigned URLs for user: {} with {} media files", userId, mediaFiles.size());

        try {
            // Validate user exists
            if (!userRepository.existsById(userId)) {
                throw new ChallengeSphereException(
                        "User not found with id: " + userId,
                        HttpStatus.NOT_FOUND,
                        logger
                );
            }

            uploadQuotaService.reserve(userId, mediaFiles);

            // Generate presigned URLs for each media file
            List<PresignedUrlsResponse.PresignedUrlInfo> presignedUrls = new ArrayList<>();
            long expirationTimeInSeconds = 3600; // 1 hour expiration

            for (GeneratePresignedUrlsRequest.MediaFileInfo mediaFile : mediaFiles) {
                try {
                    // Generate unique file key with timestamp and UUID to prevent collisions
                    String fileKey = submissionMediaKeyPolicy.createKey(
                            userId,
                            mediaFile.getMediaType(),
                            mediaFile.getContentType(),
                            System.currentTimeMillis(),
                            UUID.randomUUID());

                    issuedUploadService.issue(
                            userId, fileKey, IssuedUploadService.Purpose.SUBMISSION_PHOTO);

                    // Generate presigned URL using S3
                    PresignedUrlsResponse.PresignedUrlInfo urlInfo = getPresignedUrlInfo(mediaFile, fileKey, expirationTimeInSeconds);

                    presignedUrls.add(urlInfo);
                    logger.debug("Generated submission upload URL");

                } catch (ChallengeSphereException e) {
                    throw e;
                } catch (Exception e) {
                    logger.error("Failed to generate submission upload URL");
                    throw new ChallengeSphereException(
                            "Failed to generate presigned URL for file: " + mediaFile.getFileName(),
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            logger
                    );
                }
            }

            logger.info("Successfully generated {} presigned URLs for user: {}", presignedUrls.size(), userId);
            return PresignedUrlsResponse.success(userId, presignedUrls);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error generating presigned URLs for user: {}", userId);
            throw new ChallengeSphereException(
                    "Failed to generate presigned URLs",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    private PresignedUrlsResponse.PresignedUrlInfo getPresignedUrlInfo(GeneratePresignedUrlsRequest.MediaFileInfo mediaFile, String fileKey, long expirationTimeInSeconds) {
        return new PresignedUrlsResponse.PresignedUrlInfo(
                mediaFile.getFileName(),
                mediaFile.getMediaType().name(),
                mediaFile.getContentType().getMimeType(),
                GetSignedUrlForS3.generatePutObjectSignedUrl(
                        bucketName,
                        fileKey,
                        expirationTimeInSeconds,
                        mediaFile.getContentType(),
                        mediaFile.getFileSizeBytes()),
                fileKey,
                expirationTimeInSeconds
        );
    }

    @Override
    public String uploadSubmissionMedia(
            String userId,
            MultipartFile file,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType) throws ChallengeSphereException {
        if (file == null || file.isEmpty()) {
            throw new ChallengeSphereException("Upload file is required", HttpStatus.BAD_REQUEST, logger);
        }
        if (contentType == null) {
            throw new ChallengeSphereException("Content type is required", HttpStatus.BAD_REQUEST, logger);
        }
        validateSubmissionMedia(mediaType, contentType);
        if (file.getSize() > MAX_BACKEND_UPLOAD_BYTES) {
            throw new ChallengeSphereException(
                    "Upload file exceeds the 8 MB backend fallback limit",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (file.getContentType() != null && !file.getContentType().isBlank()
                && !contentType.getMimeType().equals(file.getContentType())) {
            throw new ChallengeSphereException("Uploaded file content type does not match requested content type", HttpStatus.BAD_REQUEST, logger);
        }
        if (!userRepository.existsById(userId)) {
            throw new ChallengeSphereException("User not found with id: " + userId, HttpStatus.NOT_FOUND, logger);
        }

        uploadQuotaService.reserve(userId, 1, file.getSize());

        String fileKey = submissionMediaKeyPolicy.createKey(
                userId,
                mediaType,
                contentType,
                System.currentTimeMillis(),
                UUID.randomUUID());

        try {
            mediaUrlService.uploadObject(fileKey, file.getBytes(), contentType.getMimeType());
            issuedUploadService.issue(
                    userId, fileKey, IssuedUploadService.Purpose.SUBMISSION_PHOTO);
            logger.info("Uploaded submission media through backend fallback for userId={}", userId);
            return fileKey;
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Backend media upload failed for userId={}", userId);
            throw new ChallengeSphereException("Failed to upload submission media", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    private void validateSubmissionPresignRequest(List<GeneratePresignedUrlsRequest.MediaFileInfo> mediaFiles) throws ChallengeSphereException {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            throw new ChallengeSphereException("At least one media file is required", HttpStatus.BAD_REQUEST, logger);
        }
        if (mediaFiles.size() > MAX_SUBMISSION_MEDIA_FILES) {
            throw new ChallengeSphereException("A submission can include at most 5 media files", HttpStatus.BAD_REQUEST, logger);
        }
        for (GeneratePresignedUrlsRequest.MediaFileInfo mediaFile : mediaFiles) {
            if (mediaFile == null) {
                throw new ChallengeSphereException("Media file details are required", HttpStatus.BAD_REQUEST, logger);
            }
            if (mediaFile.getFileName() == null || mediaFile.getFileName().isBlank()) {
                throw new ChallengeSphereException("File name is required", HttpStatus.BAD_REQUEST, logger);
            }
            if (mediaFile.getContentType() == null) {
                throw new ChallengeSphereException("Content type is required", HttpStatus.BAD_REQUEST, logger);
            }
            validateSubmissionMedia(mediaFile.getMediaType(), mediaFile.getContentType());
        }
    }

    private void validateSubmissionMedia(
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType) throws ChallengeSphereException {
        if (mediaType != MediaTypes.SUBMISSION_PHOTO) {
            throw new ChallengeSphereException("Unsupported submission media type", HttpStatus.BAD_REQUEST, logger);
        }
        if (contentType == GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4) {
            throw new ChallengeSphereException("Content type does not match submission photo media type", HttpStatus.BAD_REQUEST, logger);
        }
    }

    @Override
    public Submission getSubmissionById(String submissionId, String callerId) throws ChallengeSphereException {
        if (submissionId == null) {
            return null;
        }
        logger.debug("Fetching submission from DB: id={}", submissionId);
        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            return null;
        }
        if (!canViewSubmission(submission, callerId)) {
            throw new ChallengeSphereException(
                    "Not authorized to view this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        return withReadableMediaUrls(submission);
    }

    private List<Submission> withReadableMediaUrls(List<Submission> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return List.of();
        }

        Set<String> ownerUserIds = submissions.stream()
                .map(Submission::getUserId)
                .filter(SubmissionServiceImpl::hasText)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, User> ownersById = hydrateUsersById(ownerUserIds);
        return submissions.stream()
                .map(submission -> withReadableMediaUrls(
                        submission,
                        ownersById.get(submission.getUserId())
                ))
                .toList();
    }

    private Submission withReadableMediaUrls(Submission submission) {
        if (submission == null) {
            return null;
        }
        User owner = hasText(submission.getUserId())
                ? userRepository.findById(submission.getUserId()).orElse(null)
                : null;
        return withReadableMediaUrls(submission, owner);
    }

    private Submission withReadableMediaUrls(Submission submission, User owner) {
        Submission copy = new Submission();
        copy.setId(submission.getId());
        copy.setUserId(submission.getUserId());
        copy.setChallengeId(submission.getChallengeId());
        copy.setDayNumber(submission.getDayNumber());
        copy.setDescription(submission.getDescription());
        List<String> safeMediaKeys = submissionMediaKeyPolicy.filterOwnedKeysForRead(
                submission.getMediaUrls(),
                submissionMediaOwnerIds(submission.getUserId(), owner));
        copy.setMediaUrls(safeMediaKeys.isEmpty()
                ? null
                : mediaUrlService.toReadableMediaUrls(String.join(",", safeMediaKeys)));
        copy.setIsPrivate(submission.getIsPrivate());
        copy.setSubmissionTime(submission.getSubmissionTime());
        copy.setCreatedAt(submission.getCreatedAt());
        copy.setUpdatedAt(submission.getUpdatedAt());
        copy.setActive(submission.getActive());
        copy.setGloriesCount(submission.getGloriesCount());
        copy.setCommentsCount(submission.getCommentsCount());
        copy.setVersion(submission.getVersion());
        return copy;
    }

    private Map<String, User> hydrateUsersById(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        User::getId,
                        user -> user,
                        (left, right) -> left
                ));
    }

    private Set<String> submissionMediaOwnerIds(String currentOwnerId, User owner) {
        if (!hasText(currentOwnerId)) {
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
        return hasText(userId)
                && userId.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void ensureSubmissionOwner(Submission submission, String userId) throws ChallengeSphereException {
        if (submission == null || userId == null || !userId.equals(submission.getUserId())) {
            throw new ChallengeSphereException(
                    "You can only modify your own submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
    }

    private void validateSubmissionProof(String description, List<String> mediaKeys)
            throws ChallengeSphereException {
        if (description == null || description.isBlank()) {
            throw new ChallengeSphereException(
                    "Add a short check-in before posting",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        TextContentSafetyPolicy.requireAllowed(description, "submission description");
        if (mediaKeys == null || mediaKeys.isEmpty()) {
            throw new ChallengeSphereException(
                    "At least one submission photo is required",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private void requireSubmissionMedia(List<String> mediaKeys) throws ChallengeSphereException {
        if (mediaKeys == null || mediaKeys.isEmpty()) {
            throw new ChallengeSphereException(
                    "At least one submission photo is required",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private void moderateSubmissionMediaOrReject(String userId, List<String> mediaKeys)
            throws ChallengeSphereException {
        ContentModerationService.Result moderation;
        try {
            moderation = contentModerationService.scanStrict(mediaKeys);
        } catch (RuntimeException unavailable) {
            logger.error("Submission photo moderation failed closed for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Submission photos could not be verified for safety. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger,
                    unavailable
            );
        }

        if (moderation == null) {
            throw new ChallengeSphereException(
                    "Submission photos could not be verified for safety. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger
            );
        }
        if (moderation.verdict() != ContentModerationService.Verdict.CLEAN) {
            moderationEnforcementService.removeFlaggedMediaAndWarn(userId, moderation);
            throw new ChallengeSphereException(
                    "Submission image removed for violating the content policy. A warning was sent to your notifications.",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private void requireChallengeStarted(String challengeId) throws ChallengeSphereException {
        challengeCrewService.requireStarted(challengeId);
    }

    private UserChallenge requireActiveChallengeMembership(String userId, String challengeId)
            throws ChallengeSphereException {
        UserChallenge userChallenge = userChallengeRepository.findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "User is not a participant of this challenge. Please join the challenge first.",
                        HttpStatus.BAD_REQUEST,
                        logger
                ));

        if (Boolean.TRUE.equals(userChallenge.getBanned())) {
            throw new ChallengeSphereException(
                    "You were removed from this challenge by its creator.",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (!Boolean.TRUE.equals(userChallenge.getActive())) {
            throw new ChallengeSphereException(
                    "User has left this challenge",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        return userChallenge;
    }

    private boolean isImageMediaUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String path = value.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        return path.endsWith(".jpg")
                || path.endsWith(".jpeg")
                || path.endsWith(".png")
                || path.endsWith(".webp")
                || path.endsWith(".heic")
                || path.endsWith(".heif");
    }

    private boolean isOneOffChallenge(Challenge challenge) {
        return challenge != null && Challenge.isOneOffMode(challenge.getChallengeMode());
    }

    private Integer normalizeOneOffSubmissionDay(Integer dayNumber) throws ChallengeSphereException {
        if (dayNumber == null || dayNumber == 1) {
            return 1;
        }
        throw new ChallengeSphereException(
                "One-off challenges only accept a single completion submission.",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }

    private SubmissionResponse reconcileExistingOneOffSubmission(
            Submission existing,
            CreateSubmissionRequest request,
            List<String> requestedMediaKeys,
            boolean requestedPrivate,
            Challenge challenge
    ) throws ChallengeSphereException {
        if (!matchesOneOffReplay(
                existing, request, requestedMediaKeys, requestedPrivate
        )) {
            // Even a non-replay request is allowed to repair the invariant left
            // by an earlier partial write before receiving the normal cap error.
            markOneOffChallengeCompleted(existing.getUserId(), existing.getChallengeId());
            logger.warn(
                    "One-off submission already exists for user: {}, challenge: {}",
                    existing.getUserId(),
                    existing.getChallengeId()
            );
            throw oneOffSubmissionAlreadyExists();
        }

        finishPersistedSubmission(existing, challenge, true, null);
        List<String> existingMediaKeys = storedMediaKeys(existing);
        logger.info(
                "Resumed one-off submission workflow for submissionId={} userId={} challengeId={}",
                existing.getId(),
                existing.getUserId(),
                existing.getChallengeId()
        );
        return SubmissionResponse.success(existing, existingMediaKeys);
    }

    private boolean matchesOneOffReplay(
            Submission existing,
            CreateSubmissionRequest request,
            List<String> requestedMediaKeys,
            boolean requestedPrivate
    ) {
        return existing != null
                && Objects.equals(existing.getDescription(), request.getDescription())
                && Boolean.TRUE.equals(existing.getIsPrivate()) == requestedPrivate
                && storedMediaKeys(existing).equals(
                        mediaUrlService.toStoredMediaUrls(requestedMediaKeys)
                );
    }

    private List<String> storedMediaKeys(Submission submission) {
        if (submission == null
                || submission.getMediaUrls() == null
                || submission.getMediaUrls().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(submission.getMediaUrls().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private ChallengeSphereException oneOffSubmissionAlreadyExists() {
        return new ChallengeSphereException(
                "This one-off challenge already has an active submission.",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }

    private void finishPersistedSubmission(
            Submission submission,
            Challenge challenge,
            boolean oneOffChallenge,
            LocalDate submissionLocalDate
    ) throws ChallengeSphereException {
        String userId = submission.getUserId();
        String challengeId = submission.getChallengeId();
        boolean leaderboardRebuilt = false;
        if (oneOffChallenge) {
            markOneOffChallengeCompleted(userId, challengeId);
        } else {
            try {
                leaderboardService.rebuildLeaderboard(challengeId);
                leaderboardRebuilt = true;
                logger.debug(
                        "Rebuilt points leaderboard for user: {} in challenge: {}",
                        userId,
                        challengeId
                );
            } catch (Exception failure) {
                logger.warn(
                        "Failed to rebuild leaderboard points for user: {} in challenge: {}: {}",
                        userId,
                        challengeId,
                        failure.getClass().getSimpleName()
                );
            }
        }

        if (!Boolean.TRUE.equals(submission.getActive())) {
            return;
        }
        materializeSubmissionTimelineChunk(submission);
        try {
            Set<String> notificationRecipients = userChallengeRepository
                    .findActiveByChallengeId(challengeId)
                    .stream()
                    .map(UserChallenge::getUserId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            notificationService.notifySubmission(
                    submission, challenge, notificationRecipients
            );
        } catch (Exception failure) {
            logger.warn(
                    "Failed to create submission notifications for submissionId={}: {}",
                    submission.getId(),
                    failure.getClass().getSimpleName()
            );
        }
        if (!oneOffChallenge) {
            notifyMomentumMilestonesAfterCommit(
                    userId,
                    challengeId,
                    submissionLocalDate,
                    leaderboardRebuilt
            );
        }
    }

    private void markOneOffChallengeCompleted(String userId, String challengeId)
            throws ChallengeSphereException {
        UserChallenge latest = userChallengeRepository
                .findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge membership could not be reconciled",
                        HttpStatus.CONFLICT,
                        logger
                ));
        if (Boolean.TRUE.equals(latest.getCompleted())) {
            return;
        }
        latest.setCompleted(true);
        latest.setCompletedAt(Timestamp.from(Instant.now(clock)));
        userChallengeRepository.save(latest);
    }

    void ensureSubmissionDayAcceptsUpload(Challenge challenge, Integer dayNumber, String timezone) throws ChallengeSphereException {
        if (challenge == null) {
            throw new ChallengeSphereException(
                    "Challenge not found",
                    HttpStatus.NOT_FOUND,
                    logger
            );
        }
        if (!Boolean.TRUE.equals(challenge.getActive())) {
            throw new ChallengeSphereException(
                    "Cannot submit to an inactive challenge",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (dayNumber == null || dayNumber < 1) {
            throw new ChallengeSphereException(
                    "Day number must be greater than zero",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (challenge.getStartDate() == null) {
            throw new ChallengeSphereException(
                    "Challenge start date is required for submissions",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        LocalDate today = getClientLocalDate(timezone);
        if (today.isBefore(challenge.getStartDate())) {
            throw new ChallengeSphereException(
                    "Submissions are not allowed before the challenge starts",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (isOneOffChallenge(challenge)) {
            normalizeOneOffSubmissionDay(dayNumber);
            return;
        }
        if (challenge.getNumberOfDays() != null && dayNumber > challenge.getNumberOfDays()) {
            throw new ChallengeSphereException(
                    "Day number " + dayNumber + " exceeds challenge duration of " + challenge.getNumberOfDays() + " days",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        LocalDate challengeDay = challenge.getStartDate().plusDays(dayNumber - 1L);
        if (today.isBefore(challengeDay)) {
            throw new ChallengeSphereException(
                    "Uploads for a future challenge day are not allowed",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        LocalDate lastAllowedDate = challengeDay.plusDays(SUBMISSION_GRACE_DAYS);
        if (today.isAfter(lastAllowedDate)) {
            throw new ChallengeSphereException(
                    "Uploads for day " + dayNumber + " are only allowed through " + lastAllowedDate,
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private LocalDate getClientLocalDate(String timezone) throws ChallengeSphereException {
        String timezoneValue = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
        try {
            ZoneId zoneId = ZoneId.of(timezoneValue);
            return LocalDate.now(clock.withZone(zoneId));
        } catch (DateTimeException exception) {
            throw new ChallengeSphereException(
                    "Invalid timezone",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private String normalizeGlobalSubmissionSort(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "newest";
        }
        String normalized = sortBy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "oldest", "glories" -> normalized;
            default -> "newest";
        };
    }

    private String normalizeParticipantQuery(String participantQuery) {
        if (participantQuery == null) {
            return null;
        }
        String normalized = participantQuery.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void invalidateSubmissionCaches(Submission submission) {
        if (submission == null) {
            return;
        }
        try {
            cacheService.invalidateAllFeeds();
            cacheService.invalidateTimeline(submission.getUserId());
            cacheService.invalidateGloryCaches(submission.getId());
            cacheService.invalidateCommentsForSubmission(submission.getId());
        } catch (Exception e) {
            logger.warn("Failed to invalidate caches for submission {}: {}", submission.getId(), e.getClass().getSimpleName());
        }
    }

    private boolean canViewSubmission(Submission submission, String callerId) {
        if (callerId == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(submission.getActive())) {
            return false;
        }
        if (callerId.equals(submission.getUserId())) {
            return true;
        }
        if (userBlockRepository.existsActiveBlockBetween(callerId, submission.getUserId())) {
            return false;
        }

        Challenge challenge = challengeRepository.findById(submission.getChallengeId()).orElse(null);
        if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) {
            return false;
        }

        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) && !Boolean.TRUE.equals(submission.getIsPrivate())) {
            return true;
        }

        return userChallengeRepository.findByUserIdAndChallengeId(callerId, submission.getChallengeId())
                .map(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive())
                        && !Boolean.TRUE.equals(userChallenge.getBanned()))
                .orElse(false);
    }

    private void ensureFollowAccess(String ownerUserId, String viewerUserId, String contentType)
            throws ChallengeSphereException {
        if (viewerUserId == null) {
            throw new ChallengeSphereException(
                    "callerId is required to view this " + contentType,
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (ownerUserId.equals(viewerUserId)) {
            return;
        }
        if (userBlockRepository.existsActiveBlockBetween(ownerUserId, viewerUserId)) {
            throw new ChallengeSphereException(
                    "Not authorized to view this user's " + contentType,
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (!followRepository.existsActiveMutualFollow(viewerUserId, ownerUserId)) {
            throw new ChallengeSphereException(
                    "You can only view this user's " + contentType + " if you follow each other",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
    }

    private void materializeSubmissionTimelineChunk(Submission submission) {
        if (submission == null || submission.getId() == null) {
            return;
        }
        try {
            feedTimelineMaterializer.materializeSubmissionChunk(submission.getId());
        } catch (RuntimeException failure) {
            // The main-table pending projection remains authoritative and the scheduler retries it.
            logger.warn(
                    "Submission timeline fan-out remains pending for submissionId={}: {}",
                    submission.getId(),
                    failure.getClass().getSimpleName()
            );
        }
    }

    private void notifyMomentumMilestonesAfterCommit(
            String userId,
            String challengeId,
            LocalDate currentDate,
            boolean evaluateRank
    ) {
        Runnable notify = () -> {
            try {
                notificationService.notifyMomentumMilestonesAfterSubmissionAsync(
                        userId, challengeId, currentDate, evaluateRank);
            } catch (RuntimeException exception) {
                logger.warn("Failed to schedule momentum milestones for userId={} challengeId={}: {}",
                        userId, challengeId, exception.getClass().getSimpleName());
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

    private static int normalizeLimit(int requestedLimit, int defaultLimit) {
        int resolvedLimit = requestedLimit < 1 ? defaultLimit : requestedLimit;
        return Math.min(resolvedLimit, MAX_LIMIT);
    }

    private static String cursorBinding(String endpoint, Object... components) {
        StringBuilder binding = new StringBuilder(endpoint);
        for (Object component : components) {
            binding.append('|');
            if (component == null) {
                binding.append("N;");
                continue;
            }
            String value = component.toString();
            binding.append('S').append(value.length()).append(':').append(value).append(';');
        }
        return binding.toString();
    }

    private Map<String, AttributeValue> decodeCursor(
            String cursor,
            String queryBinding
    ) throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            return cursorCodec.decode(cursor, queryBinding).lastEvaluatedKey();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(
            String queryBinding,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(lastEvaluatedKey)
        );
    }
}
