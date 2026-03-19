package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.GloryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Service
public class GloryServiceImpl implements GloryService {

    private static final Logger logger = LogManager.getLogger(GloryServiceImpl.class);

    @Autowired
    private GloryRepository gloryRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

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
        Optional<Glory> existingGlory = gloryRepository.findBySubmissionIdAndUserId(submissionId, userId);
        boolean isGiven = false;
        if (existingGlory.isPresent()) {
            Glory glory = existingGlory.get();
            // Toggle: if active → deactivate, if inactive → reactivate
            boolean wasActive = Boolean.TRUE.equals(glory.getActive());
            glory.setActive(!wasActive);
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

        updateGloryCountAsync(submission, isGiven);

        // Invalidate glory caches for this submission
        cacheService.invalidateGloryCaches(submissionId);

        return isGiven;
    }

    @Async
    @Transactional
    public void updateGloryCountAsync(Submission submission, boolean isGiven) {
        try {
            Thread.sleep(2000);
        } catch (Exception e) {
            logger.error(e);
            return ;
        }
        try {
            if (submission != null) {
                Long currentCount = submission.getGloriesCount();
                Long newCount = isGiven ? currentCount + 1 : Math.max(0, currentCount - 1);
                submission.setGloriesCount(newCount);
                submissionRepository.save(submission);
                logger.info("Updated glory count to {} for submission: {} ({})",
                        newCount, submission.getId(), isGiven ? "increased" : "decreased");
            }
        } catch (Exception e) {
            logger.error("Error updating glory count for submission {}: {}",
                    submission.getId(), e.getMessage(), e);
        }
    }

    @Override
    public List<Glory> getGloriesForSubmission(Long submissionId) {
        logger.info("Getting glories for submission: {}", submissionId);
        return gloryRepository.findActiveBySubmissionId(submissionId);
    }

    @Override
    public boolean hasUserGivenGlory(Long submissionId, Long userId) {
        logger.info("Checking if user {} has given glory to submission {}", userId, submissionId);

        // Try cache first
        Optional<Boolean> cached = cacheService.getCachedGloryCheck(submissionId, userId);
        if (cached.isPresent()) {
            logger.debug("Cache hit for glory check: submissionId={}, userId={}", submissionId, userId);
            return cached.get();
        }

        boolean hasGlory = gloryRepository.existsBySubmissionIdAndUserId(submissionId, userId);

        // Cache the result asynchronously
        cacheService.cacheGloryCheck(submissionId, userId, hasGlory);

        return hasGlory;
    }

    @Override
    public Long getGloryCount(Long submissionId) throws ChallengeSphereException {
        logger.info("Getting glory count for submission: {}", submissionId);

        // Try cache first
        Optional<Long> cached = cacheService.getCachedGloryCount(submissionId);
        if (cached.isPresent()) {
            logger.debug("Cache hit for glory count: submissionId={}", submissionId);
            return cached.get();
        }

        Long count = gloryRepository.countActiveBySubmissionId(submissionId);

        // Cache the count asynchronously
        cacheService.cacheGloryCount(submissionId, count);

        return count;
    }
}
