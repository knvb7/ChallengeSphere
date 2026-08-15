package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.impl.SubmissionModerationJob;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public class SubmissionModerationScheduler {

    private static final Logger logger = LogManager.getLogger(SubmissionModerationScheduler.class);

    private final SubmissionRepository submissionRepository;
    private final SubmissionModerationJob moderationJob;
    private final int batchSize;

    public SubmissionModerationScheduler(
            SubmissionRepository submissionRepository,
            SubmissionModerationJob moderationJob,
            int batchSize) {
        this.submissionRepository = submissionRepository;
        this.moderationJob = moderationJob;
        this.batchSize = Math.max(1, Math.min(batchSize, 1_000));
    }

    public void moderatePendingSubmissionImages() {
        List<Long> submissionIds = submissionRepository.findSubmissionIdsAwaitingModeration(
                PageRequest.of(0, batchSize));
        if (submissionIds.isEmpty()) {
            return;
        }

        int processed = 0;
        for (Long submissionId : submissionIds) {
            try {
                if (moderationJob.moderateIfPending(submissionId)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                logger.error(
                        "Moderation transaction rolled back for submission {}; it will be retried: {}",
                        submissionId,
                        e.getClass().getSimpleName());
            }
        }
        logger.info("Submission moderation sweep completed: queued={}, processed={}",
                submissionIds.size(), processed);
    }
}
