package com.ironside.challengesphere.service.impl;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.ironside.challengesphere.util.GetSignedUrlForGcs;
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
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.SubmissionService;

@Service
public class SubmissionServiceImpl implements SubmissionService {
    private static final Logger logger = LogManager.getLogger(SubmissionServiceImpl.class);
    private static final int COLLAGE_WIDTH = 1080;
    private static final int COLLAGE_HEIGHT = 1350;
    private static final int MAX_COLLAGE_PHOTOS = 9;
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
    private ModerationFlagRepository moderationFlagRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    @Value("${gcs.bucket.region}")
    private String bucketRegion;

    @Value("${gcs.projectId}")
    private String projectId;

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
            if (activeSubmissionCount >= 5) {
                logger.warn("Submission limit reached for user: {}, challenge: {}, day: {}", userId, challengeId, dayNumber);
                throw new ChallengeSphereException(
                        "You have reached the maximum of 5 submissions for day " + dayNumber + ".",
                        HttpStatus.TOO_MANY_REQUESTS,
                        logger
                );
            }

            // Content moderation — run Vision SafeSearch on the uploaded media before
            // anything is persisted. VERY_LIKELY on adult/racy/violence bans the user
            // immediately (no submission row). LIKELY saves the submission hidden
            // (active=false) and queues a flag for human review.
            ContentModerationService.Result moderation = contentModerationService.scan(request.getMediaUrls());
            logger.info("Vision SafeSearch completed for submission upload: user={}, challenge={}, mediaCount={}, verdict={}, findings={}",
                    userId,
                    challengeId,
                    request.getMediaUrls() == null ? 0 : request.getMediaUrls().size(),
                    moderation.verdict(),
                    moderation.findings().size());
            if (moderation.verdict() == ContentModerationService.Verdict.BLOCK_AND_BAN) {
                String summary = summarizeFindings(moderation);
                logger.warn("Banning user {} for disallowed content: {}", userId, summary);
                user.setActive(false);
                user.setBannedAt(new Timestamp(System.currentTimeMillis()));
                user.setBanReason("Content policy violation: " + summary);
                userRepository.save(user);
                throw new ChallengeSphereException(
                        "Submission rejected for content policy violation. Your account has been suspended.",
                        HttpStatus.FORBIDDEN,
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
            if (request.getMediaUrls() != null && !request.getMediaUrls().isEmpty()) {
                String mediaUrlsString = String.join(",", request.getMediaUrls());
                submission.setMediaUrls(mediaUrlsString);
            }

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
            // FLAG_FOR_REVIEW → hidden until a moderator resolves the flag.
            boolean flagged = moderation.verdict() == ContentModerationService.Verdict.FLAG_FOR_REVIEW;
            submission.setActive(!flagged);
            submission.setGloriesCount(0L);
            submission.setCommentsCount(0L);
            submission.setVersion(0);

            Submission savedSubmission = submissionRepository.save(submission);

            if (flagged) {
                for (ContentModerationService.Finding f : moderation.findings()) {
                    ModerationFlag flag = new ModerationFlag();
                    flag.setSubmissionId(savedSubmission.getId());
                    flag.setUserId(userId);
                    flag.setCategory(f.category());
                    flag.setSeverity(f.severity());
                    flag.setMediaUrl(f.mediaUrl());
                    flag.setCreatedAt(new Timestamp(System.currentTimeMillis()));
                    moderationFlagRepository.save(flag);
                }
                logger.warn("Submission {} flagged for review ({} findings) — user {}",
                        savedSubmission.getId(), moderation.findings().size(), userId);
            }
            logger.info("Successfully created submission with id: {} for user: {}, challenge: {}, day: {}",
                    savedSubmission.getId(), userId, challengeId, dayNumber);

            // Don't credit points for flagged submissions — if the admin later
            // approves it, the approval flow can award the score at that point.
            if (!flagged) {
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
            submission.setMediaUrls(request.getMediaUrls() == null || request.getMediaUrls().isEmpty()
                    ? null
                    : String.join(",", request.getMediaUrls()));
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

                    // Generate presigned URL using Google Cloud Storage
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
                GetSignedUrlForGcs.generatePutObjectSignedUrl(
                        bucketName,
                        fileKey,
                        expirationTimeInSeconds,
                        mediaFile.getContentType()),
                fileKey,
                expirationTimeInSeconds
        );
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
    @Transactional(readOnly = true)
    public byte[] generateChallengeCollage(Long userId, Long challengeId) throws ChallengeSphereException {
        if (userId == null || challengeId == null) {
            throw new ChallengeSphereException(
                    "User and challenge are required to generate a collage",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        try {
            List<Submission> submissions = submissionRepository.findActiveByUserIdAndChallengeId(userId, challengeId);
            if (submissions == null || submissions.isEmpty()) {
                throw new ChallengeSphereException(
                        "Add at least one submission before generating a collage.",
                        HttpStatus.NOT_FOUND,
                        logger
                );
            }

            Challenge challenge = challengeRepository.findById(challengeId).orElse(null);
            List<BufferedImage> photos = loadCollagePhotos(submissions);
            return renderChallengeCollage(submissions, challenge, photos);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error generating collage for user: {}, challenge: {}: {}", userId, challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to generate collage",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger,
                    e
            );
        }
    }

    private List<BufferedImage> loadCollagePhotos(List<Submission> submissions) {
        List<BufferedImage> photos = new ArrayList<>();

        for (Submission submission : submissions) {
            if (photos.size() >= MAX_COLLAGE_PHOTOS) {
                break;
            }

            for (String mediaUrl : parseMediaUrls(submission.getMediaUrls())) {
                if (photos.size() >= MAX_COLLAGE_PHOTOS) {
                    break;
                }
                if (!isLikelyPhotoUrl(mediaUrl)) {
                    continue;
                }

                BufferedImage photo = readMediaImage(mediaUrl);
                if (photo != null) {
                    photos.add(photo);
                }
            }
        }

        return photos;
    }

    private List<String> parseMediaUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1).replace("\"", "");
        }

        List<String> urls = new ArrayList<>();
        for (String part : value.split(",")) {
            String url = part == null ? "" : part.trim();
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
        return urls;
    }

    private boolean isLikelyPhotoUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String path = url.split("\\?")[0].toLowerCase(Locale.ROOT);
        return !(path.endsWith(".mp4")
                || path.endsWith(".mov")
                || path.endsWith(".m4v")
                || path.endsWith(".webm")
                || path.endsWith(".avi")
                || path.endsWith(".mkv"));
    }

    private BufferedImage readMediaImage(String mediaUrl) {
        try {
            String readableUrl = mediaUrlService.toReadableMediaUrl(mediaUrl);
            URLConnection connection = URI.create(readableUrl).toURL().openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            try (InputStream inputStream = connection.getInputStream()) {
                return ImageIO.read(inputStream);
            }
        } catch (Exception e) {
            logger.warn("Skipping collage image {}: {}", mediaUrl, e.getMessage());
            return null;
        }
    }

    private byte[] renderChallengeCollage(List<Submission> submissions, Challenge challenge, List<BufferedImage> photos) throws Exception {
        List<Submission> sortedSubmissions = submissions.stream()
                .sorted(Comparator.comparing(submission -> submission.getDayNumber() == null ? Integer.MAX_VALUE : submission.getDayNumber()))
                .toList();

        BufferedImage canvas = new BufferedImage(COLLAGE_WIDTH, COLLAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            drawCollageBackground(g);
            drawCollageHeader(g, challenge);
            if (photos.isEmpty()) {
                drawTextOnlyCollagePanel(g, sortedSubmissions);
            } else {
                drawPhotoGrid(g, photos);
            }
            drawCollageStats(g, sortedSubmissions);
            drawCollageFooter(g);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(canvas, "jpg", outputStream);
        return outputStream.toByteArray();
    }

    private void drawCollageBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(250, 247, 239), 0, COLLAGE_HEIGHT, new Color(229, 235, 247)));
        g.fillRect(0, 0, COLLAGE_WIDTH, COLLAGE_HEIGHT);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16f));
        g.setColor(new Color(71, 0, 236));
        g.fillOval(-180, 70, 520, 520);
        g.setColor(new Color(255, 111, 97));
        g.fillOval(760, 860, 420, 420);
        g.setColor(new Color(24, 92, 125));
        g.fillOval(760, -110, 360, 360);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private void drawCollageHeader(Graphics2D g, Challenge challenge) {
        String title = challenge != null && challenge.getTitle() != null && !challenge.getTitle().isBlank()
                ? challenge.getTitle().trim()
                : "Challenge";

        g.setColor(new Color(27, 24, 38));
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        drawCenteredText(g, "ChallengeSphere", 82);

        g.setFont(new Font("SansSerif", Font.BOLD, 66));
        drawWrappedCenteredText(g, title, 142, 940, 72, 2);

        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        g.setColor(new Color(78, 80, 92));
        drawCenteredText(g, "Your challenge story, ready to share", 270);
    }

    private void drawPhotoGrid(Graphics2D g, List<BufferedImage> photos) {
        int x = 72;
        int y = 320;
        int width = 936;
        int height = 650;
        int gap = 18;
        int count = photos.size();

        if (count == 1) {
            drawImageCover(g, photos.get(0), x, y, width, height, 44);
            return;
        }

        if (count == 2) {
            int cellWidth = (width - gap) / 2;
            drawImageCover(g, photos.get(0), x, y, cellWidth, height, 40);
            drawImageCover(g, photos.get(1), x + cellWidth + gap, y, cellWidth, height, 40);
            return;
        }

        if (count == 3) {
            int largeWidth = (int) (width * 0.58);
            int sideWidth = width - largeWidth - gap;
            int sideHeight = (height - gap) / 2;
            drawImageCover(g, photos.get(0), x, y, largeWidth, height, 40);
            drawImageCover(g, photos.get(1), x + largeWidth + gap, y, sideWidth, sideHeight, 36);
            drawImageCover(g, photos.get(2), x + largeWidth + gap, y + sideHeight + gap, sideWidth, sideHeight, 36);
            return;
        }

        int columns = count <= 4 ? 2 : 3;
        int rows = (int) Math.ceil((double) Math.min(count, MAX_COLLAGE_PHOTOS) / columns);
        int cellWidth = (width - gap * (columns - 1)) / columns;
        int cellHeight = (height - gap * (rows - 1)) / rows;

        for (int i = 0; i < Math.min(count, MAX_COLLAGE_PHOTOS); i++) {
            int col = i % columns;
            int row = i / columns;
            drawImageCover(g, photos.get(i), x + col * (cellWidth + gap), y + row * (cellHeight + gap), cellWidth, cellHeight, 34);
        }
    }

    private void drawImageCover(Graphics2D g, BufferedImage image, int x, int y, int width, int height, int radius) {
        Shape previousClip = g.getClip();
        RoundRectangle2D roundedRect = new RoundRectangle2D.Double(x, y, width, height, radius, radius);
        g.setClip(roundedRect);

        double scale = Math.max((double) width / image.getWidth(), (double) height / image.getHeight());
        int drawWidth = (int) Math.ceil(image.getWidth() * scale);
        int drawHeight = (int) Math.ceil(image.getHeight() * scale);
        int drawX = x + (width - drawWidth) / 2;
        int drawY = y + (height - drawHeight) / 2;
        g.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
        g.setClip(previousClip);

        g.setStroke(new BasicStroke(5f));
        g.setColor(new Color(255, 255, 255, 210));
        g.draw(roundedRect);
    }

    private void drawTextOnlyCollagePanel(Graphics2D g, List<Submission> submissions) {
        int x = 92;
        int y = 330;
        int width = 896;
        int height = 640;

        g.setPaint(new GradientPaint(x, y, new Color(32, 28, 54), x + width, y + height, new Color(71, 0, 236)));
        g.fill(new RoundRectangle2D.Double(x, y, width, height, 46, 46));

        g.setColor(new Color(255, 255, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 54));
        drawWrappedText(g, "Progress worth sharing", x + 62, y + 110, width - 124, 62, 2);

        String description = submissions.stream()
                .map(Submission::getDescription)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("Your submissions are ready to become a story.");

        g.setFont(new Font("SansSerif", Font.PLAIN, 32));
        g.setColor(new Color(235, 232, 255));
        drawWrappedText(g, description, x + 62, y + 240, width - 124, 44, 5);

        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.setColor(new Color(255, 223, 128));
        drawWrappedText(g, "Keep building. Keep showing up.", x + 62, y + 550, width - 124, 36, 1);
    }

    private void drawCollageStats(Graphics2D g, List<Submission> submissions) {
        int uploads = submissions.size();
        long days = submissions.stream()
                .map(Submission::getDayNumber)
                .filter(day -> day != null && day > 0)
                .distinct()
                .count();
        long glories = submissions.stream()
                .map(Submission::getGloriesCount)
                .filter(count -> count != null)
                .mapToLong(Long::longValue)
                .sum();

        int x = 92;
        int y = 1040;
        int width = 896;
        int height = 150;
        g.setColor(new Color(255, 255, 255, 224));
        g.fill(new RoundRectangle2D.Double(x, y, width, height, 36, 36));

        drawStat(g, x, y, width / 3, uploads, uploads == 1 ? "Upload" : "Uploads");
        drawStat(g, x + width / 3, y, width / 3, days, days == 1 ? "Day" : "Days");
        drawStat(g, x + (2 * width / 3), y, width / 3, glories, "Glories");
    }

    private void drawStat(Graphics2D g, int x, int y, int width, long value, String label) {
        g.setColor(new Color(27, 24, 38));
        g.setFont(new Font("SansSerif", Font.BOLD, 42));
        drawCenteredTextInBox(g, String.valueOf(value), x, y + 56, width);
        g.setColor(new Color(96, 96, 110));
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        drawCenteredTextInBox(g, label, x, y + 100, width);
    }

    private void drawCollageFooter(Graphics2D g) {
        g.setColor(new Color(45, 45, 55));
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        drawCenteredText(g, "Share your progress on Instagram", 1250);
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g.setColor(new Color(99, 99, 112));
        drawCenteredText(g, "Generated from your ChallengeSphere submissions", 1294);
    }

    private void drawCenteredText(Graphics2D g, String text, int y) {
        FontMetrics metrics = g.getFontMetrics();
        int x = (COLLAGE_WIDTH - metrics.stringWidth(text)) / 2;
        g.drawString(text, x, y);
    }

    private void drawCenteredTextInBox(Graphics2D g, String text, int x, int y, int width) {
        FontMetrics metrics = g.getFontMetrics();
        int textX = x + (width - metrics.stringWidth(text)) / 2;
        g.drawString(text, textX, y);
    }

    private void drawWrappedCenteredText(Graphics2D g, String text, int y, int maxWidth, int lineHeight, int maxLines) {
        List<String> lines = wrapText(g, text, maxWidth, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            drawCenteredText(g, lines.get(i), y + i * lineHeight);
        }
    }

    private void drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
        List<String> lines = wrapText(g, text, maxWidth, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), x, y + i * lineHeight);
        }
    }

    private List<String> wrapText(Graphics2D g, String text, int maxWidth, int maxLines) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        FontMetrics metrics = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line = new StringBuilder(candidate);
                continue;
            }

            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            line = new StringBuilder(word);
            if (lines.size() == maxLines) {
                break;
            }
        }

        if (!line.isEmpty() && lines.size() < maxLines) {
            lines.add(line.toString());
        }

        if (lines.size() == maxLines && metrics.stringWidth(lines.get(lines.size() - 1)) > maxWidth) {
            String last = lines.get(lines.size() - 1);
            while (last.length() > 3 && metrics.stringWidth(last + "...") > maxWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(lines.size() - 1, last + "...");
        }

        return lines;
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
