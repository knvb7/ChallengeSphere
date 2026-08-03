package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.impl.SubmissionModerationJob;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "app.moderation.submission-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SubmissionModerationScheduler {

    private static final Logger logger = LogManager.getLogger(SubmissionModerationScheduler.class);

    private final SubmissionRepository submissionRepository;
    private final SubmissionModerationJob moderationJob;
    private final int batchSize;

    public SubmissionModerationScheduler(
            SubmissionRepository submissionRepository,
            SubmissionModerationJob moderationJob,
            @Value("${app.moderation.submission-sweep.batch-size:100}") int batchSize) {
        this.submissionRepository = submissionRepository;
        this.moderationJob = moderationJob;
        this.batchSize = Math.max(1, Math.min(batchSize, 1_000));
    }

    @Scheduled(cron = "${app.moderation.submission-sweep.cron:0 */2 * * * *}")
    public void moderatePendingSubmissionImages() {
        List<Long> submissionIds = submissionRepository.findSubmissionIdsAwaitingModeration(
                PageRequest.of(0, batchSize));
        if (submissionIds.isEmpty()) {
            return;
        }

        int processed = 0;
        for (Long submissionId : submissionIds) {
            if (moderationJob.moderateIfPending(submissionId)) {
                processed++;
            }
        }
        logger.info("Submission moderation sweep completed: queued={}, processed={}",
                submissionIds.size(), processed);
    }
}
