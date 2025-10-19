package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.service.SubmissionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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

            return SubmissionsResponse.create(paginatedSubmissions, page, pageSize, totalCount);

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

            return SubmissionsResponse.create(paginatedSubmissions, page, pageSize, totalCount);

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

            return SubmissionsResponse.create(paginatedSubmissions, page, pageSize, totalCount);

        } catch (Exception e) {
            logger.error("Error getting submissions for user {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get submissions",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }
}
