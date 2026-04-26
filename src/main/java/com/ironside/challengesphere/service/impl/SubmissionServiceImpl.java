package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ironside.challengesphere.util.Constants;
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
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.SubmissionService;

@Service
public class SubmissionServiceImpl implements SubmissionService {
    private static final Logger logger = LogManager.getLogger(SubmissionServiceImpl.class);

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

            if (!challenge.getActive()) {
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
            if (challenge.getNumberOfDays() != null && dayNumber > challenge.getNumberOfDays()) {
                throw new ChallengeSphereException(
                        "Day number " + dayNumber + " exceeds challenge duration of " + challenge.getNumberOfDays() + " days",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            // Serialize concurrent writers for the same (user, challenge, day) tuple so the
            // 5-per-day cap is atomic. The lock is scoped to this transaction — two requests
            // that land on the same tuple queue at the lock, and the second only proceeds
            // once the first has committed (and its row is visible to the count below).
            long lockKey = submissionDayLockKey(userId, challengeId, dayNumber);
            submissionRepository.acquireSubmissionDayLock(lockKey);

            // Enforce max 5 submissions per day per user per challenge
            List<Submission> existingSubmissions = submissionRepository.findByUserChallengeAndDay(userId, challengeId, dayNumber);
            if (existingSubmissions.size() >= 5) {
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

            submission.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);

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
    public SubmissionsResponse getSubmissionsByUserAndChallenge(Long userId, Long challengeId, int page, int pageSize) throws ChallengeSphereException {
        logger.info("Getting submissions for user: {}, challenge: {}, page: {}, pageSize: {}", userId, challengeId, page, pageSize);

        try {
            // Try cache first for first page (most common request)
            // String cacheKey = USER_CHALLENGE_SUBMISSIONS_PREFIX + userId + ":" + challengeId;
            // if (page == 1) {
            //     SubmissionsResponse cachedResponse = cacheService.get(cacheKey + ":page1:" + pageSize, SubmissionsResponse.class);
            //     if (cachedResponse != null) {
            //         logger.debug("Cache hit for user-challenge submissions: userId={}, challengeId={}", userId, challengeId);
            //         return cachedResponse;
            //     }
            // }

            Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.ASC, "day_number"));
            Page<Submission> submissionPage = submissionRepository.findByUserIdAndChallengeId(userId, challengeId, pageable);
            int totalCount = (int) submissionPage.getTotalElements();

            logger.info("Found {} submissions for user: {}, challenge: {} (showing page {}/{})",
                    totalCount, userId, challengeId, page, submissionPage.getTotalPages());

            SubmissionsResponse response = SubmissionsResponse.create(submissionPage.getContent(), page, pageSize, totalCount);

            // Cache first page results
            // if (page == 1) {
            //     cacheService.save(cacheKey + ":page1:" + pageSize, response, SUBMISSIONS_LIST_TTL);
            // }

            return response;

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
    public SubmissionsResponse getSubmissionsByChallenge(Long challengeId, int page, int pageSize) throws ChallengeSphereException {
        logger.info("Getting submissions for challenge: {}, page: {}, pageSize: {}", challengeId, page, pageSize);

        try {
            // Try cache first for first page
            // String cacheKey = CHALLENGE_SUBMISSIONS_PREFIX + challengeId;
            // if (page == 1) {
            //     SubmissionsResponse cachedResponse = cacheService.get(cacheKey + ":page1:" + pageSize, SubmissionsResponse.class);
            //     if (cachedResponse != null) {
            //         logger.debug("Cache hit for challenge submissions: challengeId={}", challengeId);
            //         return cachedResponse;
            //     }
            // }

            Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "submission_time"));
            Page<Submission> submissionPage = submissionRepository.findActiveByChallengeId(challengeId, pageable);
            int totalCount = (int) submissionPage.getTotalElements();

            logger.info("Found {} active submissions for challenge: {} (showing page {}/{})",
                    totalCount, challengeId, page, submissionPage.getTotalPages());

            SubmissionsResponse response = SubmissionsResponse.create(submissionPage.getContent(), page, pageSize, totalCount);

            // Cache first page results
            // if (page == 1) {
            //     cacheService.save(cacheKey + ":page1:" + pageSize, response, SUBMISSIONS_LIST_TTL);
            // }

            return response;

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
    public SubmissionsResponse getSubmissionsByUser(Long userId, int page, int pageSize) throws ChallengeSphereException {
        logger.info("Getting submissions for user: {}, page: {}, pageSize: {}", userId, page, pageSize);

        try {
            // Try cache first for first page
            // String cacheKey = USER_SUBMISSIONS_PREFIX + userId;
            // if (page == 1) {
            //     SubmissionsResponse cachedResponse = cacheService.get(cacheKey + ":page1:" + pageSize, SubmissionsResponse.class);
            //     if (cachedResponse != null) {
            //         logger.debug("Cache hit for user submissions: userId={}", userId);
            //         return cachedResponse;
            //     }
            // }

            Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "submission_time"));
            Page<Submission> submissionPage = submissionRepository.findActiveByUserId(userId, pageable);
            int totalCount = (int) submissionPage.getTotalElements();

            logger.info("Found {} active submissions for user: {} (showing page {}/{})",
                    totalCount, userId, page, submissionPage.getTotalPages());

            SubmissionsResponse response = SubmissionsResponse.create(submissionPage.getContent(), page, pageSize, totalCount);

            // Cache first page results
            // if (page == 1) {
            //     cacheService.save(cacheKey + ":page1:" + pageSize, response, SUBMISSIONS_LIST_TTL);
            // }

            return response;

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

    private static PresignedUrlsResponse.PresignedUrlInfo getPresignedUrlInfo(GeneratePresignedUrlsRequest.MediaFileInfo mediaFile, String fileKey, long expirationTimeInSeconds) {
        return new PresignedUrlsResponse.PresignedUrlInfo(
                mediaFile.getFileName(),
                mediaFile.getMediaType().name(),
                mediaFile.getContentType().getMimeType(),
                GetSignedUrlForGcs.generateV4PutObjectSignedUrl(
                        Constants.GCSBUCKETFORSUBMISSIONS,
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
    public Submission getSubmissionById(Long submissionId, Long callerId) throws ChallengeSphereException {
        if (submissionId == null) {
            return null;
        }
        logger.debug("Fetching submission from DB: id={}", submissionId);
        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            return null;
        }
        if (Boolean.TRUE.equals(submission.getIsPrivate()) && !callerCanViewPrivate(submission, callerId)) {
            throw new ChallengeSphereException(
                    "Not authorized to view this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        return submission;
    }

    private boolean callerCanViewPrivate(Submission submission, Long callerId) {
        if (callerId == null) return false;
        if (callerId.equals(submission.getUserId())) return true;
        return followRepository.existsByFollowerIdAndFollowingIdAndActive(callerId, submission.getUserId());
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
