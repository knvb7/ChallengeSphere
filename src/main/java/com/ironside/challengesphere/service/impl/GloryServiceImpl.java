package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.GloryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

        // Validate submission exists without loading the full entity into persistence context
        if (!submissionRepository.existsById(submissionId)) {
            throw new ChallengeSphereException(
                    "Submission not found with id: " + submissionId,
                    HttpStatus.NOT_FOUND,
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

        // Invalidate glory caches for this submission
        // cacheService.invalidateGloryCaches(submissionId);

        return isGiven;
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
        // Optional<Boolean> cached = cacheService.getCachedGloryCheck(submissionId, userId);
        // if (cached.isPresent()) {
        //     logger.debug("Cache hit for glory check: submissionId={}, userId={}", submissionId, userId);
        //     return cached.get();
        // }

        boolean hasGlory = gloryRepository.existsBySubmissionIdAndUserId(submissionId, userId);

        // Cache the result asynchronously
        // cacheService.cacheGloryCheck(submissionId, userId, hasGlory);

        return hasGlory;
    }

    @Override
    @Transactional
    public boolean toggleUnglory(Long submissionId, Long userId) throws ChallengeSphereException {
        logger.info("Toggle unglory for submission: {} by user: {}", submissionId, userId);

        if (!submissionRepository.existsById(submissionId)) {
            throw new ChallengeSphereException("Submission not found with id: " + submissionId, HttpStatus.NOT_FOUND, logger);
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

        // Try cache first
        // Optional<Long> cached = cacheService.getCachedGloryCount(submissionId);
        // if (cached.isPresent()) {
        //     logger.debug("Cache hit for glory count: submissionId={}", submissionId);
        //     return cached.get();
        // }

        Long count = gloryRepository.countActiveBySubmissionId(submissionId);

        // Cache the count asynchronously
        // cacheService.cacheGloryCount(submissionId, count);

        return count;
    }
}
