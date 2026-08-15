package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.impl.SubmissionModerationJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Production trigger for retrying pending and failed submission moderation.
 * The sweep implementation remains directly constructible for non-scheduled
 * callers while this component owns runtime scheduling configuration.
 */
@Component
@ConditionalOnProperty(
        name = "app.moderation.submission-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SubmissionModerationRetryScheduler {

    private final SubmissionModerationScheduler sweep;

    public SubmissionModerationRetryScheduler(
            SubmissionRepository submissionRepository,
            SubmissionModerationJob moderationJob,
            @Value("${app.moderation.submission-sweep.batch-size:100}") int batchSize) {
        this.sweep = new SubmissionModerationScheduler(
                submissionRepository,
                moderationJob,
                batchSize);
    }

    @Scheduled(cron = "${app.moderation.submission-sweep.cron:0 */2 * * * *}")
    public void moderatePendingSubmissionImages() {
        sweep.moderatePendingSubmissionImages();
    }
}
