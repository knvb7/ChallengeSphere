package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.GloryUserResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.GloryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class GloryServiceImpl implements GloryService {

    private static final Logger logger = LogManager.getLogger(GloryServiceImpl.class);

    @Autowired
    private GloryRepository gloryRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Override
    @Transactional
    public boolean toggleGlory(Long submissionId, Long userId) throws ChallengeSphereException {
        logger.info("Toggle glory for submission: {} by user: {}", submissionId, userId);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (canInteractWithSubmission(submission, userId)) {
            throw new ChallengeSphereException(
                    "Not authorized to glory this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        Optional<Glory> existingGlory = gloryRepository.findBySubmissionIdAndUserId(submissionId, userId);
        boolean isGiven = false;
        if (existingGlory.isPresent()) {
            Glory glory = existingGlory.get();
            // Toggle: if active → deactivate, if inactive → reactivate
            boolean wasActive = Boolean.TRUE.equals(glory.getActive());
            glory.setActive(!wasActive);
            // Giving glory clears any unglory — can't have both states active
            if (!wasActive) glory.setUngloried(false);
            gloryRepository.save(glory);
            isGiven = !wasActive;
            logger.info("Toggled glory active={} for submission: {} by user: {}",
                    isGiven, submissionId, userId);
        } else {
            Glory newGlory = new Glory();
            newGlory.setSubmissionId(submissionId);
            newGlory.setUserId(userId);
            newGlory.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            newGlory.setActive(true);
            gloryRepository.save(newGlory);
            isGiven = true;
            logger.info("Created new glory for submission: {} by user: {}", submissionId, userId);
        }

        // Atomically update glory count directly in DB (avoids optimistic locking issues)
        if (isGiven)
            submissionRepository.incrementGloryCount(submissionId);
        else
            submissionRepository.decrementGloryCount(submissionId);
        logger.info("Updated glory count for submission: {} ({})", submissionId, isGiven ? "incremented" : "decremented");

        invalidateInteractionCaches(submission);

        return isGiven;
    }


    @Override
    public List<Glory> getGloriesForSubmission(Long submissionId) {
        logger.info("Getting glories for submission: {}", submissionId);
        return gloryRepository.findActiveBySubmissionId(submissionId);
    }

    @Override
    public Page<GloryUserResponse> getGloriesForSubmission(Long submissionId, Pageable pageable, Long callerId) throws ChallengeSphereException {
        logger.info("Getting glories page for submission: {}, page: {}, size: {}",
                submissionId, pageable.getPageNumber(), pageable.getPageSize());
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        if (canInteractWithSubmission(submission, callerId)) {
            throw new ChallengeSphereException("Not authorized to view glories for this submission",
                    HttpStatus.FORBIDDEN, logger);
        }
        Page<Glory> glories = gloryRepository.findActiveBySubmissionId(submissionId, pageable);
        List<Long> userIds = glories.getContent().stream()
                .map(Glory::getUserId)
                .distinct()
                .toList();
        Map<Long, User> usersById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        return glories.map(glory -> GloryUserResponse.from(glory, usersById.get(glory.getUserId())));
    }

    @Override
    public boolean hasUserGivenGlory(Long submissionId, Long userId) {
        logger.info("Checking if user {} has given glory to submission {}", userId, submissionId);

        boolean hasGlory = gloryRepository.existsBySubmissionIdAndUserId(submissionId, userId);

        return hasGlory;
    }

    @Override
    @Transactional
    public boolean toggleUnglory(Long submissionId, Long userId) throws ChallengeSphereException {
        logger.info("Toggle unglory for submission: {} by user: {}", submissionId, userId);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId, HttpStatus.NOT_FOUND, logger));
        if (submission.getUserId() != null && submission.getUserId().equals(userId)) {
            throw new ChallengeSphereException(
                    "Cannot unglory your own submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (canInteractWithSubmission(submission, userId)) {
            throw new ChallengeSphereException(
                    "Not authorized to unglory this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        Optional<Glory> existing = gloryRepository.findBySubmissionIdAndUserId(submissionId, userId);
        boolean isUngloried;

        if (existing.isPresent()) {
            Glory glory = existing.get();
            boolean wasUngloried = Boolean.TRUE.equals(glory.getUngloried());
            glory.setUngloried(!wasUngloried);
            // Unglorying clears glory — can't have both states active
            if (!wasUngloried) {
                if (Boolean.TRUE.equals(glory.getActive())) {
                    glory.setActive(false);
                    submissionRepository.decrementGloryCount(submissionId);
                }
            }
            gloryRepository.save(glory);
            isUngloried = !wasUngloried;
        } else {
            Glory glory = new Glory();
            glory.setSubmissionId(submissionId);
            glory.setUserId(userId);
            glory.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            glory.setActive(false);
            glory.setUngloried(true);
            gloryRepository.save(glory);
            isUngloried = true;
        }

        logger.info("Toggled unglory={} for submission: {} by user: {}", isUngloried, submissionId, userId);
        invalidateInteractionCaches(submission);
        return isUngloried;
    }

    @Override
    public boolean hasUserUngloried(Long submissionId, Long userId) throws ChallengeSphereException {
        logger.info("Checking if user {} has ungloried submission {}", userId, submissionId);
        return gloryRepository.existsUngloriedBySubmissionIdAndUserId(submissionId, userId);
    }

    @Override
    public Long getGloryCount(Long submissionId) throws ChallengeSphereException {
        logger.info("Getting glory count for submission: {}", submissionId);

        Long count = gloryRepository.countActiveBySubmissionId(submissionId);

        return count;
    }

    private void invalidateInteractionCaches(Submission submission) {
        if (submission == null) {
            return;
        }
        try {
            cacheService.invalidateGloryCaches(submission.getId());
            cacheService.invalidateAllFeeds();
        } catch (Exception e) {
            logger.warn("Failed to invalidate interaction caches for submission {}: {}", submission.getId(), e.getMessage());
        }
    }

    private boolean canInteractWithSubmission(Submission submission, Long viewerId) {
        if (submission == null || viewerId == null) {
            return true;
        }
        if (viewerId.equals(submission.getUserId())) {
            return false;
        }
        if (userBlockRepository.existsActiveBlockBetween(viewerId, submission.getUserId())) {
            return true;
        }
        if (!Boolean.TRUE.equals(submission.getActive())) {
            return true;
        }

        Challenge challenge = challengeRepository.findById(submission.getChallengeId()).orElse(null);
        if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) {
            return true;
        }

        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) && !Boolean.TRUE.equals(submission.getIsPrivate())) {
            return false;
        }

        return userChallengeRepository.findByUserIdAndChallengeId(viewerId, submission.getChallengeId())
                .map(userChallenge -> !Boolean.TRUE.equals(userChallenge.getActive()))
                .orElse(true);
    }
}
