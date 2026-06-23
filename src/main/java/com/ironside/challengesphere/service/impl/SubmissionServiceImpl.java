package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
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
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.SubmissionService;

@Service
public class SubmissionServiceImpl implements SubmissionService {
    private static final Logger logger = LogManager.getLogger(SubmissionServiceImpl.class);
    private static final int SUBMISSION_GRACE_DAYS = 2;

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
    private SubmissionModerationJob submissionModerationJob;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Override
    @Transactional
    public SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        Long challengeId = request.getChallengeId();
        Integer dayNumber = request.getDayNumber();

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

            mediaUrlService.applyPrivateImageCacheHeaders(request.getMediaUrls());

            // Create submission
            Submission submission = new Submission();
            submission.setUserId(userId);
            submission.setChallengeId(challengeId);
            submission.setDayNumber(dayNumber);
            submission.setDescription(request.getDescription());

            // Convert media URLs list to comma-separated string
            submission.setMediaUrls(mediaUrlService.toStoredMediaUrlsString(request.getMediaUrls()));
            submission.setProofPayload(toProofPayloadJson(request.getProofPayload()));

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
                        request.getMediaUrls() == null ? List.of() : List.copyOf(request.getMediaUrls())
                );
            } catch (Exception e) {
                logger.warn("Failed to enqueue moderation for submission {}: {}", savedSubmission.getId(), e.getMessage());
            }

            if (oneOffChallenge) {
                markOneOffChallengeCompleted(userChallenge);
            } else {
                try {
                    leaderboardService.incrementScore(challengeId, userId, 10, "New submission for day " + dayNumber);
                    logger.debug("Updated leaderboard score for user: {} in challenge: {}", userId, challengeId);
                } catch (Exception e) {
                    // Don't fail the submission if leaderboard update fails
                    logger.warn("Failed to update leaderboard score for user: {} in challenge: {}: {}",
                            userId, challengeId, e.getMessage());
                }
            }

            if (Boolean.TRUE.equals(savedSubmission.getActive())) {
                Set<Long> notificationRecipients = fanOutSubmissionToFeedTimelines(savedSubmission, challenge, userId);
                try {
                    notificationService.notifySubmission(savedSubmission, challenge, notificationRecipients);
                } catch (Exception e) {
                    logger.warn("Failed to create submission notifications for submissionId={}: {}",
                            savedSubmission.getId(), e.getMessage());
                }
            }

            return SubmissionResponse.success(savedSubmission, request.getMediaUrls());

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error creating submission for user: {}, challenge: {}: {}", userId, challengeId, e.getMessage(), e);
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
                List<Submission> activeSubmissions = submissionRepository.findActiveByUserIdAndChallengeId(userId, challengeId);
                int totalCount = activeSubmissions.size();
                int offset = Math.max(0, page - 1) * pageSize;
                List<Submission> paginatedSubmissions = activeSubmissions.stream()
                        .skip(offset)
                        .limit(pageSize)
                        .toList();

                logger.info("Found {} submissions for user: {}, challenge: {} (showing page {}/{})",
                        totalCount, userId, challengeId, page, totalCount == 0 ? 0 : (int) Math.ceil((double) totalCount / pageSize));

                return SubmissionsResponse.create(withReadableMediaUrls(paginatedSubmissions), page, pageSize, totalCount);
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
            logger.error("Error getting submissions for user: {}, challenge: {}: {}", userId, challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getRandomChallengeSubmissionsByDay(Long challengeId, Integer dayNumber, int page, int pageSize, Long callerId) throws ChallengeSphereException {
        logger.info("Getting random challenge submissions: challenge={}, day={}, page={}, pageSize={}", challengeId, dayNumber, page, pageSize);

        if (dayNumber == null || dayNumber < 1) {
            throw new ChallengeSphereException(
                    "Day number must be greater than zero",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        try {
            Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize);
            Page<Submission> submissionPage = submissionRepository.findVisibleByChallengeIdAndDayNumber(
                    challengeId,
                    dayNumber,
                    callerId,
                    pageable
            );
            List<Submission> paginatedSubmissions = new ArrayList<>(submissionPage.getContent());
            Collections.shuffle(paginatedSubmissions);

            logger.info("Found {} visible submissions for challenge: {}, day: {}", submissionPage.getTotalElements(), challengeId, dayNumber);
            return SubmissionsResponse.create(withReadableMediaUrls(paginatedSubmissions), page, pageSize, (int) submissionPage.getTotalElements());
        } catch (Exception e) {
            logger.error("Error getting random submissions for challenge {}, day {}: {}", challengeId, dayNumber, e.getMessage(), e);
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

            ContentModerationService.Result moderation = contentModerationService.scan(request.getMediaUrls());
            if (moderation.verdict() == ContentModerationService.Verdict.BLOCK_AND_BAN) {
                User user = userRepository.findById(userId).orElse(null);
                if (user != null) {
                    user.setActive(false);
                    user.setBannedAt(new Timestamp(System.currentTimeMillis()));
                    user.setBanReason("Content policy violation: " + summarizeFindings(moderation));
                    userRepository.save(user);
                }
                throw new ChallengeSphereException(
                        "Submission rejected for content policy violation. Your account has been suspended.",
                        HttpStatus.FORBIDDEN,
                        logger
                );
            }

            mediaUrlService.applyPrivateImageCacheHeaders(request.getMediaUrls());
            submission.setDescription(request.getDescription());
            submission.setMediaUrls(mediaUrlService.toStoredMediaUrlsString(request.getMediaUrls()));
            submission.setProofPayload(toProofPayloadJson(request.getProofPayload()));
            if (request.getIsPrivate() != null) {
                submission.setIsPrivate(request.getIsPrivate());
            }
            submission.setSubmissionTime(request.getSubmissionDate() != null ? request.getSubmissionDate() : Timestamp.from(Instant.now()));
            submission.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            submission.setActive(moderation.verdict() != ContentModerationService.Verdict.FLAG_FOR_REVIEW);

            Submission savedSubmission = submissionRepository.save(submission);
            invalidateSubmissionCaches(savedSubmission);
            logger.info("Successfully reuploaded submission: {} for user: {}", submissionId, userId);
            return SubmissionResponse.success(savedSubmission, request.getMediaUrls());
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error reuploading submission: {} for user: {}: {}", submissionId, userId, e.getMessage(), e);
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

            submission.setActive(false);
            submission.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            submissionRepository.save(submission);
            invalidateSubmissionCaches(submission);
            try {
                leaderboardService.rebuildLeaderboard(submission.getChallengeId());
            } catch (Exception e) {
                logger.warn("Failed to rebuild leaderboard after deleting submission {}: {}", submissionId, e.getMessage());
            }
            logger.info("Successfully deleted submission: {} for user: {}", submissionId, userId);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error deleting submission: {} for user: {}: {}", submissionId, userId, e.getMessage(), e);
            throw new ChallengeSphereException("Failed to delete submission", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionsResponse getSubmissionsByChallenge(Long challengeId, int page, int pageSize, Long callerId) throws ChallengeSphereException {
        logger.info("Getting submissions for challenge: {}, page: {}, pageSize: {}", challengeId, page, pageSize);

        try {
            List<Submission> visibleSubmissions = submissionRepository.findActiveByChallengeId(challengeId).stream()
                    .filter(submission -> canViewSubmission(submission, callerId))
                    .toList();
            int totalCount = visibleSubmissions.size();
            int offset = Math.max(0, page - 1) * pageSize;
            List<Submission> paginatedSubmissions = visibleSubmissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            logger.info("Found {} active submissions for challenge: {} (showing page {}/{})",
                    totalCount, challengeId, page, totalCount == 0 ? 0 : (int) Math.ceil((double) totalCount / pageSize));
            return SubmissionsResponse.create(withReadableMediaUrls(paginatedSubmissions), page, pageSize, totalCount);

        } catch (Exception e) {
            logger.error("Error getting submissions for challenge {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
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
            logger.error("Error getting submissions for user {}: {}", userId, e.getMessage(), e);
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
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    String uniqueId = UUID.randomUUID().toString();
                    String fileExtension = getFileExtension(mediaFile.getFileName());
                    String fileKey = String.format("submissions/user_%d/%s/%s_%s%s",
                            userId,
                            mediaFile.getMediaType().name(),
                            timestamp,
                            uniqueId,
                            fileExtension);

                    // Generate presigned URL using S3
                    PresignedUrlsResponse.PresignedUrlInfo urlInfo = getPresignedUrlInfo(mediaFile, fileKey, expirationTimeInSeconds);

                    presignedUrls.add(urlInfo);
                    logger.debug("Generated presigned URL for file: {} with key: {}", mediaFile.getFileName(), fileKey);

                } catch (Exception e) {
                    logger.error("Error generating presigned URL for file: {}", mediaFile.getFileName(), e);
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
            logger.error("Error generating presigned URLs for user: {}", userId, e);
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
        if (mediaType != MediaTypes.SUBMISSION_PHOTO && mediaType != MediaTypes.SUBMISSION_VIDEO) {
            throw new ChallengeSphereException("Unsupported submission media type", HttpStatus.BAD_REQUEST, logger);
        }
        if (contentType == null) {
            throw new ChallengeSphereException("Content type is required", HttpStatus.BAD_REQUEST, logger);
        }
        if (!userRepository.existsById(userId)) {
            throw new ChallengeSphereException("User not found with id: " + userId, HttpStatus.NOT_FOUND, logger);
        }

        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String fileKey = String.format("submissions/user_%d/%s/%d_%s%s",
                userId,
                mediaType.name(),
                System.currentTimeMillis(),
                UUID.randomUUID(),
                getFileExtension(fileName));

        try {
            mediaUrlService.uploadObject(fileKey, file.getBytes(), contentType.getMimeType());
            logger.info("Uploaded submission media through backend fallback for user: {}, key: {}", userId, fileKey);
            return fileKey;
        } catch (Exception e) {
            logger.error("Backend media upload failed for user: {}, key: {}", userId, fileKey, e);
            throw new ChallengeSphereException("Failed to upload submission media", HttpStatus.INTERNAL_SERVER_ERROR, logger, e);
        }
    }

    /**
     * Extract file extension from filename
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
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
            logger.warn("Failed to invalidate caches for submission {}: {}", submission.getId(), e.getMessage());
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

    private String toProofPayloadJson(JsonNode proofPayload) throws ChallengeSphereException {
        if (proofPayload == null || proofPayload.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(proofPayload);
        } catch (JsonProcessingException e) {
            throw new ChallengeSphereException("Invalid proof payload", HttpStatus.BAD_REQUEST, logger, e);
        }
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
