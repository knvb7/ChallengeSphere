package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.projection.SubmissionPreviewProjection;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.SubmissionService;

@Service
public class SubmissionServiceImpl implements SubmissionService {
    private static final Logger logger = LogManager.getLogger(SubmissionServiceImpl.class);
    private static final int SUBMISSION_GRACE_DAYS = 2;
    private static final int MAX_SUBMISSION_MEDIA_FILES = 5;
    private static final long MAX_BACKEND_UPLOAD_BYTES = 8L * 1024L * 1024L;

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
    private ContentModerationService contentModerationService;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private SubmissionMediaKeyPolicy submissionMediaKeyPolicy;

    @Autowired
    private SubmissionModerationJob submissionModerationJob;

    @Autowired
    private AuthTokenStore authTokenStore;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Override
    @Transactional
    public SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        Long challengeId = request.getChallengeId();
        Integer dayNumber = request.getDayNumber();
        List<String> mediaKeys = submissionMediaKeyPolicy.validateAndNormalize(userId, request.getMediaUrls());

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

            // Validate user is a participant in the challenge
            List<UserChallenge> userChallenges = userChallengeRepository.findByUserIdAndChallengeId(userId, challengeId)
                    .stream().toList();

            if (userChallenges.isEmpty()) {
                throw new ChallengeSphereException(
                        "User is not a participant of this challenge. Please join the challenge first.",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            UserChallenge userChallenge = userChallenges.get(0);
            if (!userChallenge.getActive()) {
                throw new ChallengeSphereException(
                        "User has left this challenge",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

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
                ensureSubmissionDayAcceptsUpload(challenge, dayNumber);
            }

            // Serialize concurrent writers for the same (user, challenge, day) tuple so the
            // 5-per-day cap is atomic. The lock is scoped to this transaction — two requests
            // that land on the same tuple queue at the lock, and the second only proceeds
            // once the first has committed (and its row is visible to the count below).
            long lockKey = submissionDayLockKey(userId, challengeId, dayNumber);
            submissionRepository.acquireSubmissionDayLock(lockKey);

            // Enforce max 5 submissions per day per user per challenge
            List<Submission> existingSubmissions = submissionRepository.findByUserChallengeAndDay(userId, challengeId, dayNumber);
            long activeSubmissionCount = existingSubmissions.stream()
                    .filter(submission -> Boolean.TRUE.equals(submission.getActive()))
                    .count();
            boolean shouldAwardLeaderboardSubmissionPoints = !oneOffChallenge && activeSubmissionCount == 0;
            if (oneOffChallenge && activeSubmissionCount >= 1) {
                logger.warn("One-off submission already exists for user: {}, challenge: {}", userId, challengeId);
                throw new ChallengeSphereException(
                        "This one-off challenge already has an active submission.",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            if (!oneOffChallenge && activeSubmissionCount >= 5) {
                logger.warn("Submission limit reached for user: {}, challenge: {}, day: {}", userId, challengeId, dayNumber);
                throw new ChallengeSphereException(
                        "You have reached the maximum of 5 submissions for day " + dayNumber + ".",
                        HttpStatus.TOO_MANY_REQUESTS,
                        logger
                );
            }

            mediaUrlService.applyPrivateImageCacheHeaders(mediaKeys);

            // Create submission
            Submission submission = new Submission();
            submission.setUserId(userId);
            submission.setChallengeId(challengeId);
            submission.setDayNumber(dayNumber);
            submission.setDescription(request.getDescription());

            // Convert media URLs list to comma-separated string
            submission.setMediaUrls(mediaUrlService.toStoredMediaUrlsString(mediaKeys));

            boolean challengeIsPrivate = Boolean.TRUE.equals(challenge.getIsPrivate());
            if (challengeIsPrivate) {
                submission.setIsPrivate(true);
            } else {
                submission.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);
            }

            // Set submission date - defaults to today if not provided
            Timestamp submissionTime = request.getSubmissionDate() != null ? request.getSubmissionDate() : Timestamp.from(Instant.now());
            submission.setSubmissionTime(submissionTime);
            submission.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            submission.setActive(true);
            submission.setGloriesCount(0L);
            submission.setCommentsCount(0L);
            submission.setVersion(0);

            Submission savedSubmission = submissionRepository.save(submission);

            logger.info("Successfully created submission with id: {} for user: {}, challenge: {}, day: {}",
                    savedSubmission.getId(), userId, challengeId, dayNumber);

            try {
                submissionModerationJob.moderateSubmissionAsync(
                        savedSubmission.getId(),
                        userId,
                        challengeId,
                        mediaKeys
                );
            } catch (Exception e) {
                logger.warn("Failed to enqueue moderation for submission {}: {}", savedSubmission.getId(), e.getClass().getSimpleName());
            }

            if (oneOffChallenge) {
                markOneOffChallengeCompleted(userChallenge);
            } else if (shouldAwardLeaderboardSubmissionPoints) {
                try {
                    leaderboardService.incrementScore(challengeId, userId, 10, "New submission for day " + dayNumber);
                    logger.debug("Updated leaderboard score for user: {} in challenge: {}", userId, challengeId);
                } catch (Exception e) {
                    // Don't fail the submission if leaderboard update fails
                    logger.warn("Failed to update leaderboard score for user: {} in challenge: {}: {}",
                            userId, challengeId, e.getClass().getSimpleName());
                }
            } else {
                logger.debug(
                        "Skipped leaderboard submission points for user: {} in challenge: {}, day: {} because the day was already counted",
                        userId, challengeId, dayNumber);
            }

            if (Boolean.TRUE.equals(savedSubmission.getActive())) {
                Set<Long> notificationRecipients = fanOutSubmissionToFeedTimelines(savedSubmission, challenge, userId);
                try {
                    notificationService.notifySubmission(savedSubmission, challenge, notificationRecipients);
                } catch (Exception e) {
                    logger.warn("Failed to create submission notifications for submissionId={}: {}",
                            savedSubmission.getId(), e.getClass().getSimpleName());
                }
            }

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
    public SubmissionsResponse getSubmissionsByUserAndChallenge(Long userId, Long challengeId, int page, int pageSize, Long callerId) throws ChallengeSphereException {
        logger.info("Getting submissions for user: {}, challenge: {}, page: {}, pageSize: {}", userId, challengeId, page, pageSize);

        try {
            if (userId.equals(callerId)) {
                Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize);
                Page<Submission> submissionPage = submissionRepository.findByUserIdAndChallengeId(userId, challengeId, pageable);
                int totalCount = (int) submissionPage.getTotalElements();

                logger.info("Found {} submissions for user: {}, challenge: {} (showing page {}/{})",
                        totalCount, userId, challengeId, page, submissionPage.getTotalPages());

                return SubmissionsResponse.create(withReadableMediaUrls(submissionPage.getContent()), page, pageSize, totalCount);
            }

            List<Submission> visibleSubmissions = submissionRepository.findActiveByUserIdAndChallengeId(userId, challengeId).stream()
                    .filter(submission -> canViewSubmission(submission, callerId))
                    .toList();
            int totalCount = visibleSubmissions.size();
            int offset = Math.max(0, page - 1) * pageSize;
            List<Submission> paginatedSubmissions = visibleSubmissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            logger.info("Found {} visible submissions for user: {}, challenge: {}",
                    totalCount, userId, challengeId);
            return SubmissionsResponse.create(withReadableMediaUrls(paginatedSubmissions), page, pageSize, totalCount);

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
            Long challengeId,
            Integer dayNumber,
            int page,
            int pageSize,
            Long callerId,
            String sortBy,
            String participantQuery
    ) throws ChallengeSphereException {
        String normalizedSort = normalizeGlobalSubmissionSort(sortBy);
        String normalizedParticipantQuery = normalizeParticipantQuery(participantQuery);
        logger.info("Getting global challenge submissions: challenge={}, day={}, page={}, pageSize={}, sortBy={}",
                challengeId, dayNumber, page, pageSize, normalizedSort);

        if (dayNumber == null || dayNumber < 1) {
            throw new ChallengeSphereException(
                    "Day number must be greater than zero",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        try {
            Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize);
            Page<Submission> submissionPage = switch (normalizedSort) {
                case "oldest" -> submissionRepository.findVisibleByChallengeIdAndDayNumberOldest(
                        challengeId,
                        dayNumber,
                        callerId,
                        normalizedParticipantQuery,
                        pageable
                );
                case "glories" -> submissionRepository.findVisibleByChallengeIdAndDayNumberMostGlories(
                        challengeId,
                        dayNumber,
                        callerId,
                        normalizedParticipantQuery,
                        pageable
                );
                default -> submissionRepository.findVisibleByChallengeIdAndDayNumberNewest(
                        challengeId,
                        dayNumber,
                        callerId,
                        normalizedParticipantQuery,
                        pageable
                );
            };

            logger.info("Found {} visible submissions for challenge: {}, day: {}", submissionPage.getTotalElements(), challengeId, dayNumber);
            return SubmissionsResponse.create(withReadableMediaUrls(submissionPage.getContent()), page, pageSize, (int) submissionPage.getTotalElements());
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
    @Transactional
    public SubmissionResponse reuploadSubmission(Long submissionId, CreateSubmissionRequest request) throws ChallengeSphereException {
        if (submissionId == null) {
            throw new ChallengeSphereException("Submission ID is required", HttpStatus.BAD_REQUEST, logger);
        }
        Long userId = request.getUserId();
        List<String> mediaKeys = submissionMediaKeyPolicy.validateAndNormalize(userId, request.getMediaUrls());
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
            ensureSubmissionDayAcceptsUpload(challenge, submission.getDayNumber());

            ContentModerationService.Result moderation = contentModerationService.scan(mediaKeys);
            if (moderation.verdict() == ContentModerationService.Verdict.BLOCK_AND_BAN) {
                User user = userRepository.findById(userId).orElse(null);
                if (user != null) {
                    user.setActive(false);
                    user.setBannedAt(new Timestamp(System.currentTimeMillis()));
                    user.setBanReason("Content policy violation: " + summarizeFindings(moderation));
                    userRepository.save(user);
                    authTokenStore.revokeAllForUser(userId);
                }
                throw new ChallengeSphereException(
                        "Submission rejected for content policy violation. Your account has been suspended.",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            mediaUrlService.applyPrivateImageCacheHeaders(mediaKeys);
            submission.setDescription(request.getDescription());
            submission.setMediaUrls(mediaUrlService.toStoredMediaUrlsString(mediaKeys));
            if (request.getIsPrivate() != null) {
                submission.setIsPrivate(request.getIsPrivate());
            }
            submission.setSubmissionTime(request.getSubmissionDate() != null ? request.getSubmissionDate() : Timestamp.from(Instant.now()));
            submission.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            submission.setActive(moderation.verdict() != ContentModerationService.Verdict.FLAG_FOR_REVIEW);

            Submission savedSubmission = submissionRepository.save(submission);
            invalidateSubmissionCaches(savedSubmission);
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
    public void deleteSubmission(Long submissionId, Long userId) throws ChallengeSphereException {
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

            submission.setActive(false);
            submission.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            submissionRepository.save(submission);
            invalidateSubmissionCaches(submission);
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
    public SubmissionsResponse getSubmissionsByChallenge(Long challengeId, int page, int pageSize, Long callerId, String sortBy, String participantQuery) throws ChallengeSphereException {
        String normalizedSort = normalizeGlobalSubmissionSort(sortBy);
        String normalizedParticipantQuery = normalizeParticipantQuery(participantQuery);
        logger.info("Getting submissions for challenge: {}, page: {}, pageSize: {}, sortBy: {}", challengeId, page, pageSize, normalizedSort);

        try {
            Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize);
            Page<Submission> submissionPage = switch (normalizedSort) {
                case "oldest" -> submissionRepository.findVisibleByChallengeIdOldest(
                        challengeId,
                        callerId,
                        normalizedParticipantQuery,
                        pageable
                );
                case "glories" -> submissionRepository.findVisibleByChallengeIdMostGlories(
                        challengeId,
                        callerId,
                        normalizedParticipantQuery,
                        pageable
                );
                default -> submissionRepository.findVisibleByChallengeIdNewest(
                        challengeId,
                        callerId,
                        normalizedParticipantQuery,
                        pageable
                );
            };

            logger.info("Found {} active submissions for challenge: {} (showing page {}/{})",
                    submissionPage.getTotalElements(), challengeId, page, submissionPage.getTotalPages());
            return SubmissionsResponse.create(withReadableMediaUrls(submissionPage.getContent()), page, pageSize, (int) submissionPage.getTotalElements());

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
            List<Long> challengeIds,
            Long userId,
            Long callerId) throws ChallengeSphereException {
        List<Long> ids = challengeIds == null ? List.of() : challengeIds.stream()
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

        Map<Long, List<SubmissionPreviewItemResponse>> items = new LinkedHashMap<>();
        Map<Long, Long> totals = new HashMap<>();
        ids.forEach(id -> items.put(id, new ArrayList<>()));

        for (SubmissionPreviewProjection row :
                submissionRepository.findSubmissionPreviews(ids, userId, callerId)) {
            List<SubmissionPreviewItemResponse> challengeItems = items.get(row.getChallengeId());
            if (challengeItems == null || challengeItems.size() >= 4) {
                continue;
            }
            String firstMedia = mediaUrlService.toReadableMediaUrlList(row.getMediaUrls())
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
            totals.put(row.getChallengeId(), row.getTotalCount());
        }

        return ids.stream()
                .map(id -> new ChallengeSubmissionPreviewResponse(
                        id,
                        List.copyOf(items.get(id)),
                        totals.getOrDefault(id, 0L)
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getSubmissionsByUser(Long userId, int page, int pageSize, Long callerId) throws ChallengeSphereException {
        logger.info("Getting submissions for user: {}, page: {}, pageSize: {}", userId, page, pageSize);

        try {
            ensureFollowAccess(userId, callerId, "posts");

            if (userId.equals(callerId)) {
                Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "submission_time"));
                Page<Submission> submissionPage = submissionRepository.findActiveByUserId(userId, pageable);
                int totalCount = (int) submissionPage.getTotalElements();

                logger.info("Found {} active submissions for user: {} (showing page {}/{})",
                        totalCount, userId, page, submissionPage.getTotalPages());

                return SubmissionsResponse.create(withReadableMediaUrls(submissionPage.getContent()), page, pageSize, totalCount);
            }

            List<Submission> visibleSubmissions = submissionRepository.findPublicByUserId(userId);
            int totalCount = visibleSubmissions.size();
            int offset = Math.max(0, page - 1) * pageSize;
            List<Submission> paginatedSubmissions = visibleSubmissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            logger.info("Found {} visible submissions for user: {}", totalCount, userId);
            return SubmissionsResponse.create(withReadableMediaUrls(paginatedSubmissions), page, pageSize, totalCount);

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
        Long userId = request.getUserId();
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

                    // Generate presigned URL using S3
                    PresignedUrlsResponse.PresignedUrlInfo urlInfo = getPresignedUrlInfo(mediaFile, fileKey, expirationTimeInSeconds);

                    presignedUrls.add(urlInfo);
                    logger.debug("Generated submission upload URL");

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
                        mediaFile.getContentType()),
                fileKey,
                expirationTimeInSeconds
        );
    }

    @Override
    public String uploadSubmissionMedia(
            Long userId,
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

        String fileKey = submissionMediaKeyPolicy.createKey(
                userId,
                mediaType,
                contentType,
                System.currentTimeMillis(),
                UUID.randomUUID());

        try {
            mediaUrlService.uploadObject(fileKey, file.getBytes(), contentType.getMimeType());
            logger.info("Uploaded submission media through backend fallback for userId={}", userId);
            return fileKey;
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
        if (mediaType != MediaTypes.SUBMISSION_PHOTO && mediaType != MediaTypes.SUBMISSION_VIDEO) {
            throw new ChallengeSphereException("Unsupported submission media type", HttpStatus.BAD_REQUEST, logger);
        }
        if (mediaType == MediaTypes.SUBMISSION_PHOTO && contentType == GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4) {
            throw new ChallengeSphereException("Content type does not match submission photo media type", HttpStatus.BAD_REQUEST, logger);
        }
        if (mediaType == MediaTypes.SUBMISSION_VIDEO && contentType != GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4) {
            throw new ChallengeSphereException("Content type does not match submission video media type", HttpStatus.BAD_REQUEST, logger);
        }
    }

    @Override
    public Submission getSubmissionById(Long submissionId, Long callerId) throws ChallengeSphereException {
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
        return submissions.stream()
                .map(this::withReadableMediaUrls)
                .toList();
    }

    private Submission withReadableMediaUrls(Submission submission) {
        if (submission == null) {
            return null;
        }
        Submission copy = new Submission();
        copy.setId(submission.getId());
        copy.setUserId(submission.getUserId());
        copy.setChallengeId(submission.getChallengeId());
        copy.setDayNumber(submission.getDayNumber());
        copy.setDescription(submission.getDescription());
        copy.setMediaUrls(mediaUrlService.toReadableMediaUrls(submission.getMediaUrls()));
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

    private void ensureSubmissionOwner(Submission submission, Long userId) throws ChallengeSphereException {
        if (submission == null || userId == null || !userId.equals(submission.getUserId())) {
            throw new ChallengeSphereException(
                    "You can only modify your own submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
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

    private void markOneOffChallengeCompleted(UserChallenge userChallenge) {
        if (userChallenge == null || Boolean.TRUE.equals(userChallenge.getCompleted())) {
            return;
        }
        userChallenge.setCompleted(true);
        userChallenge.setCompletedAt(new Timestamp(System.currentTimeMillis()));
        userChallengeRepository.save(userChallenge);
    }

    private void ensureSubmissionDayAcceptsUpload(Challenge challenge, Integer dayNumber) throws ChallengeSphereException {
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
        if (challenge.getStartDate() == null) {
            throw new ChallengeSphereException(
                    "Challenge start date is required for submissions",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        LocalDate challengeDay = challenge.getStartDate().plusDays(dayNumber - 1L);
        LocalDate today = LocalDate.now();
        LocalDate lastAllowedDate = challengeDay.plusDays(SUBMISSION_GRACE_DAYS);
        if (today.isAfter(lastAllowedDate)) {
            throw new ChallengeSphereException(
                    "Uploads for day " + dayNumber + " are only allowed through " + lastAllowedDate,
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

    private boolean canViewSubmission(Submission submission, Long callerId) {
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
                .map(UserChallenge::getActive)
                .orElse(false);
    }

    private void ensureFollowAccess(Long ownerUserId, Long viewerUserId, String contentType)
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

    private Set<Long> fanOutSubmissionToFeedTimelines(Submission submission, Challenge challenge, Long authorUserId) {
        if (submission == null || submission.getId() == null || challenge == null || authorUserId == null) {
            return Set.of();
        }

        double score = submission.getCreatedAt() != null
                ? submission.getCreatedAt().getTime()
                : System.currentTimeMillis();

        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            if (Boolean.TRUE.equals(submission.getIsPrivate())) {
                return Set.of();
            }
            Set<Long> followerIds = followRepository.findActiveFollowsByFollowingId(authorUserId).stream()
                    .map(Follow::getFollowerId)
                    .filter(followerId -> !userBlockRepository.existsActiveBlockBetween(authorUserId, followerId))
                    .collect(java.util.stream.Collectors.toSet());
            cacheService.fanOutToFollowerTimelines(followerIds, submission.getId(), score);
            return followerIds;
        }

        Set<Long> challengeMemberIds = new HashSet<>(userChallengeRepository.findActiveByChallengeId(challenge.getId()).stream()
                .map(UserChallenge::getUserId)
                .collect(java.util.stream.Collectors.toSet()));
        challengeMemberIds.remove(authorUserId);
        challengeMemberIds.removeIf(memberId -> userBlockRepository.existsActiveBlockBetween(authorUserId, memberId));
        cacheService.fanOutToFollowerTimelines(challengeMemberIds, submission.getId(), score);
        return challengeMemberIds;
    }

    private static String summarizeFindings(ContentModerationService.Result result) {
        if (result.findings().isEmpty()) return "unspecified";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(3, result.findings().size());
        for (int i = 0; i < n; i++) {
            ContentModerationService.Finding f = result.findings().get(i);
            if (i > 0) sb.append(", ");
            sb.append(f.category()).append('=').append(f.severity());
        }
        if (result.findings().size() > n) sb.append(", +").append(result.findings().size() - n).append(" more");
        return sb.toString();
    }

    // Derive a stable 64-bit key from (userId, challengeId, dayNumber) for the advisory lock.
    // Mixing is just to spread the keyspace — collisions only cost us a brief serialization,
    // never correctness.
    private static long submissionDayLockKey(Long userId, Long challengeId, Integer dayNumber) {
        long u = userId == null ? 0L : userId;
        long c = challengeId == null ? 0L : challengeId;
        long d = dayNumber == null ? 0L : dayNumber.longValue();
        long key = u * 0x9E3779B97F4A7C15L;
        key ^= Long.rotateLeft(c, 21);
        key ^= Long.rotateLeft(d, 42);
        return key;
    }
}
