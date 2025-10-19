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
            gloryRepository.delete(existingGlory.get());
            logger.info("Toggled existing glory to active={} for submission: {} by user: {}",
                    false, submissionId, userId);
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
        return gloryRepository.existsBySubmissionIdAndUserId(submissionId, userId);
    }

    @Override
    public Long getGloryCount(Long submissionId) throws ChallengeSphereException {
        logger.info("Getting glory count for submission: {}", submissionId);
        return gloryRepository.countActiveBySubmissionId(submissionId);
    }
}
