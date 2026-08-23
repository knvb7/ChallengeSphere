package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.impl.FeedTimelineMaterializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Continuously resumes bounded durable timeline fan-out and follow backfill work. */
@Component
@ConditionalOnProperty(
        name = "app.feed.timeline-materialization.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class FeedTimelineMaterializationScheduler {

    private static final Logger logger = LogManager.getLogger(FeedTimelineMaterializationScheduler.class);
    private static final int WORK_ITEMS_PER_SWEEP = 25;

    private final SubmissionRepository submissionRepository;
    private final FollowRepository followRepository;
    private final FeedTimelineMaterializer materializer;

    public FeedTimelineMaterializationScheduler(
            SubmissionRepository submissionRepository,
            FollowRepository followRepository,
            FeedTimelineMaterializer materializer
    ) {
        this.submissionRepository = submissionRepository;
        this.followRepository = followRepository;
        this.materializer = materializer;
    }

    @Scheduled(fixedDelayString = "${app.feed.timeline-materialization.fixed-delay-ms:1000}")
    public void resumePendingTimelineWork() {
        for (Submission submission : submissionRepository.findPendingFeedTimelineFanouts(
                WORK_ITEMS_PER_SWEEP
        )) {
            try {
                materializer.materializeSubmissionChunk(submission.getId());
            } catch (RuntimeException failure) {
                logger.warn(
                        "Submission timeline fan-out remains pending for submissionId={}: {}",
                        submission.getId(),
                        failure.getClass().getSimpleName()
                );
            }
        }
        for (Follow follow : followRepository.findPendingTimelineBackfills(WORK_ITEMS_PER_SWEEP)) {
            try {
                materializer.materializeFollowChunk(follow.getId());
            } catch (RuntimeException failure) {
                logger.warn(
                        "Follow timeline backfill remains pending for followId={}: {}",
                        follow.getId(),
                        failure.getClass().getSimpleName()
                );
            }
        }
    }
}
