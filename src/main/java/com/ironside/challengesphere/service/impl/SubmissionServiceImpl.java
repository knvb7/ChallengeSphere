package com.ironside.challengesphere.service.impl;

import java.sql.Timestamp;
import java.time.Duration;
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
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.SubmissionService;

@Service
public class SubmissionServiceImpl implements SubmissionService {
    private static final Logger logger = LogManager.getLogger(SubmissionServiceImpl.class);

    // Cache key prefixes
    private static final String SUBMISSION_PREFIX = "submission:";
    private static final String USER_CHALLENGE_SUBMISSIONS_PREFIX = "submissions:user_challenge:";
    private static final String CHALLENGE_SUBMISSIONS_PREFIX = "submissions:challenge:";
    private static final String USER_SUBMISSIONS_PREFIX = "submissions:user:";
    private static final String TRENDING_SUBMISSIONS_PREFIX = "submissions:trending:";
    
    // Cache TTL values
    private static final Duration SUBMISSION_TTL = Duration.ofMinutes(30);
    private static final Duration SUBMISSIONS_LIST_TTL = Duration.ofMinutes(15);
    private static final Duration TRENDING_TTL = Duration.ofMinutes(5);

    @Autowired
    private SubmissionRepository submissionRepository;
    
    @Autowired
    private ChallengeRepository challengeRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserChallengeRepository userChallengeRepository;
    
    @Autowired
    private RedisCacheServiceImpl cacheService;
    
    @Autowired
    private LeaderboardService leaderboardService;

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

            // Check if submission already exists for this day
            List<Submission> existingSubmissions = submissionRepository.findByUserChallengeAndDay(userId, challengeId, dayNumber);
            if (!existingSubmissions.isEmpty()) {
                logger.warn("Submission already exists for user: {}, challenge: {}, day: {}", userId, challengeId, dayNumber);
                throw new ChallengeSphereException(
                        "A submission already exists for day " + dayNumber + ". Please update the existing submission instead.",
                        HttpStatus.CONFLICT,
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
            submission.setActive(true);
            submission.setGloriesCount(0L);
            submission.setCommentsCount(0L);
            submission.setVersion(0);

            Submission savedSubmission = submissionRepository.save(submission);
            logger.info("Successfully created submission with id: {} for user: {}, challenge: {}, day: {}",
                    savedSubmission.getId(), userId, challengeId, dayNumber);

            // Cache the new submission
            cacheSubmission(savedSubmission);
            
            // Invalidate list caches (new submission affects these lists)
            invalidateSubmissionListCaches(userId, challengeId);
            
            // Update leaderboard score for this user (async-safe)
            try {
                leaderboardService.incrementScore(challengeId, userId, 10, "New submission for day " + dayNumber);
                logger.debug("Updated leaderboard score for user: {} in challenge: {}", userId, challengeId);
            } catch (Exception e) {
                // Don't fail the submission if leaderboard update fails
                logger.warn("Failed to update leaderboard score for user: {} in challenge: {}: {}", 
                    userId, challengeId, e.getMessage());
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
            String cacheKey = USER_CHALLENGE_SUBMISSIONS_PREFIX + userId + ":" + challengeId;
            if (page == 1) {
                SubmissionsResponse cachedResponse = cacheService.get(cacheKey + ":page1:" + pageSize, SubmissionsResponse.class);
                if (cachedResponse != null) {
                    logger.debug("Cache hit for user-challenge submissions: userId={}, challengeId={}", userId, challengeId);
                    return cachedResponse;
                }
            }

            List<Submission> allSubmissions = submissionRepository.findByUserIdAndChallengeId(userId, challengeId);

            // Apply pagination
            int totalCount = allSubmissions.size();
            int offset = (page - 1) * pageSize;
            List<Submission> paginatedSubmissions = allSubmissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            logger.info("Found {} submissions for user: {}, challenge: {} (showing page {}/{})",
                    totalCount, userId, challengeId, page, (int) Math.ceil((double) totalCount / pageSize));

            SubmissionsResponse response = SubmissionsResponse.create(paginatedSubmissions, page, pageSize, totalCount);
            
            // Cache first page results
            if (page == 1) {
                cacheService.save(cacheKey + ":page1:" + pageSize, response, SUBMISSIONS_LIST_TTL);
            }
            
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
            String cacheKey = CHALLENGE_SUBMISSIONS_PREFIX + challengeId;
            if (page == 1) {
                SubmissionsResponse cachedResponse = cacheService.get(cacheKey + ":page1:" + pageSize, SubmissionsResponse.class);
                if (cachedResponse != null) {
                    logger.debug("Cache hit for challenge submissions: challengeId={}", challengeId);
                    return cachedResponse;
                }
            }

            List<Submission> allSubmissions = submissionRepository.findActiveByChallengeId(challengeId);

            // Apply pagination
            int totalCount = allSubmissions.size();
            int offset = (page - 1) * pageSize;
            List<Submission> paginatedSubmissions = allSubmissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            logger.info("Found {} active submissions for challenge: {} (showing page {}/{})",
                    totalCount, challengeId, page, (int) Math.ceil((double) totalCount / pageSize));

            SubmissionsResponse response = SubmissionsResponse.create(paginatedSubmissions, page, pageSize, totalCount);
            
            // Cache first page results
            if (page == 1) {
                cacheService.save(cacheKey + ":page1:" + pageSize, response, SUBMISSIONS_LIST_TTL);
            }
            
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
            String cacheKey = USER_SUBMISSIONS_PREFIX + userId;
            if (page == 1) {
                SubmissionsResponse cachedResponse = cacheService.get(cacheKey + ":page1:" + pageSize, SubmissionsResponse.class);
                if (cachedResponse != null) {
                    logger.debug("Cache hit for user submissions: userId={}", userId);
                    return cachedResponse;
                }
            }

            List<Submission> allSubmissions = submissionRepository.findActiveByUserId(userId);

            // Apply pagination
            int totalCount = allSubmissions.size();
            int offset = (page - 1) * pageSize;
            List<Submission> paginatedSubmissions = allSubmissions.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .toList();

            logger.info("Found {} active submissions for user: {} (showing page {}/{})",
                    totalCount, userId, page, (int) Math.ceil((double) totalCount / pageSize));

            SubmissionsResponse response = SubmissionsResponse.create(paginatedSubmissions, page, pageSize, totalCount);
            
            // Cache first page results
            if (page == 1) {
                cacheService.save(cacheKey + ":page1:" + pageSize, response, SUBMISSIONS_LIST_TTL);
            }
            
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
                GetSignedUrlForGcs.generateV4PutObjectSignedUrl(Constants.GCSBUCKETFORSUBMISSIONS, mediaFile.getMediaType().name(),
                        expirationTimeInSeconds, mediaFile.getContentType()),
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

    // ==================== CACHE HELPER METHODS ====================

    /**
     * Cache a single submission
     */
    private void cacheSubmission(Submission submission) {
        if (submission == null || submission.getId() == null) {
            return;
        }
        try {
            String cacheKey = SUBMISSION_PREFIX + submission.getId();
            cacheService.save(cacheKey, submission, SUBMISSION_TTL);
            logger.debug("Cached submission: id={}", submission.getId());
        } catch (Exception e) {
            logger.warn("Failed to cache submission: id={}, error={}", submission.getId(), e.getMessage());
        }
    }

    /**
     * Get a submission from cache by ID
     * @return cached submission or null if not found
     */
    private Submission getCachedSubmission(Long submissionId) {
        if (submissionId == null) {
            return null;
        }
        try {
            String cacheKey = SUBMISSION_PREFIX + submissionId;
            return cacheService.get(cacheKey, Submission.class);
        } catch (Exception e) {
            logger.warn("Failed to get cached submission: id={}, error={}", submissionId, e.getMessage());
            return null;
        }
    }

    /**
     * Get submission by ID with cache-first strategy
     * @return submission from cache or database
     */
    @Override
    public Submission getSubmissionById(Long submissionId) {
        if (submissionId == null) {
            return null;
        }
        
        // Try cache first
        Submission cached = getCachedSubmission(submissionId);
        if (cached != null) {
            logger.debug("Cache hit for submission: id={}", submissionId);
            return cached;
        }
        
        // Fallback to database
        logger.debug("Cache miss for submission: id={}, fetching from DB", submissionId);
        return submissionRepository.findById(submissionId)
                .map(submission -> {
                    cacheSubmission(submission);
                    return submission;
                })
                .orElse(null);
    }

    /**
     * Invalidate submission list caches when a new submission is created/updated/deleted
     * This ensures fresh data is fetched on next request
     */
    private void invalidateSubmissionListCaches(Long userId, Long challengeId) {
        try {
            // Invalidate user-challenge combination cache
            String userChallengeCachePattern = USER_CHALLENGE_SUBMISSIONS_PREFIX + userId + ":" + challengeId + "*";
            cacheService.deleteByPattern(userChallengeCachePattern);
            
            // Invalidate challenge submissions cache
            String challengeCachePattern = CHALLENGE_SUBMISSIONS_PREFIX + challengeId + "*";
            cacheService.deleteByPattern(challengeCachePattern);
            
            // Invalidate user submissions cache
            String userCachePattern = USER_SUBMISSIONS_PREFIX + userId + "*";
            cacheService.deleteByPattern(userCachePattern);
            
            // Invalidate trending cache (new submission affects trending)
            String trendingCachePattern = TRENDING_SUBMISSIONS_PREFIX + "*";
            cacheService.deleteByPattern(trendingCachePattern);
            
            logger.debug("Invalidated submission list caches for userId={}, challengeId={}", userId, challengeId);
        } catch (Exception e) {
            logger.warn("Failed to invalidate submission list caches: userId={}, challengeId={}, error={}", 
                userId, challengeId, e.getMessage());
        }
    }

    /**
     * Invalidate a single submission cache
     */
    private void invalidateSubmissionCache(Long submissionId) {
        if (submissionId == null) {
            return;
        }
        try {
            String cacheKey = SUBMISSION_PREFIX + submissionId;
            cacheService.delete(cacheKey);
            logger.debug("Invalidated submission cache: id={}", submissionId);
        } catch (Exception e) {
            logger.warn("Failed to invalidate submission cache: id={}, error={}", submissionId, e.getMessage());
        }
    }
}
