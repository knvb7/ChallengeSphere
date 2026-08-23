package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import com.ironside.challengesphere.repository.FeedTimelineRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resumable main-table materialization for following feeds. Each invocation
 * advances at most one 100-item DynamoDB page and persists its continuation.
 */
@Service
public class FeedTimelineMaterializer {

    private static final int CHUNK_SIZE = 100;
    private static final int DRAIN_BATCH_SIZE = 100;
    private static final String SUBMISSION_LEASE_SCOPE = "FEED_TIMELINE_SUBMISSION";
    private static final String FOLLOW_LEASE_SCOPE = "FEED_TIMELINE_FOLLOW";

    private final FeedTimelineRepository timelineRepository;
    private final SubmissionRepository submissionRepository;
    private final FollowRepository followRepository;
    private final ConditionalKeyLease leases;

    public FeedTimelineMaterializer(
            FeedTimelineRepository timelineRepository,
            SubmissionRepository submissionRepository,
            FollowRepository followRepository,
            ConditionalKeyLease leases
    ) {
        this.timelineRepository = timelineRepository;
        this.submissionRepository = submissionRepository;
        this.followRepository = followRepository;
        this.leases = leases;
    }

    public boolean materializeSubmissionChunk(String submissionId) {
        if (!hasText(submissionId)) {
            return false;
        }
        try (ConditionalKeyLease.Lease ignored = leases.acquireLease(
                SUBMISSION_LEASE_SCOPE,
                submissionId
        )) {
            Submission submission = submissionRepository.findById(submissionId).orElse(null);
            if (submission == null
                    || !Boolean.TRUE.equals(submission.getActive())
                    || submission.getFeedTimelineFanoutCompletedAt() != null) {
                return false;
            }

            String cursor = submission.getFeedTimelineFanoutCursor();
            DynamoPage<Follow> followers = followRepository.findActiveFollowersPage(
                    submission.getUserId(),
                    CHUNK_SIZE,
                    cursor
            );
            Set<String> recipients = followers.items().stream()
                    .map(Follow::getFollowerId)
                    .filter(FeedTimelineMaterializer::hasText)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (cursor == null) {
                recipients.add(submission.getUserId());
            }
            timelineRepository.putForViewers(recipients, submission);

            String nextCursor = FeedTimelineRepository.continuationSortKey(
                    followers.lastEvaluatedKey()
            );
            submissionRepository.updateFeedTimelineFanoutProgress(
                    submissionId,
                    nextCursor,
                    !followers.hasMore()
            );
            return true;
        } catch (CannotAcquireLockException alreadyRunning) {
            return false;
        }
    }

    public boolean materializeFollowChunk(String followId) {
        if (!hasText(followId)) {
            return false;
        }
        try (ConditionalKeyLease.Lease ignored = leases.acquireLease(
                FOLLOW_LEASE_SCOPE,
                followId
        )) {
            Follow follow = followRepository.findById(followId).orElse(null);
            if (follow == null
                    || !Boolean.TRUE.equals(follow.getActive())
                    || follow.getFeedTimelineBackfillCompletedAt() != null) {
                return false;
            }

            DynamoPage<Submission> submissions = submissionRepository.findActiveByUserTimelinePage(
                    follow.getFollowingId(),
                    CHUNK_SIZE,
                    follow.getFeedTimelineBackfillCursor()
            );
            timelineRepository.putForViewer(follow.getFollowerId(), submissions.items());
            String nextCursor = FeedTimelineRepository.continuationSortKey(
                    submissions.lastEvaluatedKey()
            );
            followRepository.updateTimelineBackfillProgress(
                    followId,
                    nextCursor,
                    !submissions.hasMore()
            );
            return true;
        } catch (CannotAcquireLockException alreadyRunning) {
            return false;
        }
    }

    /**
     * Synchronously drains all resumable work before a migration may be marked
     * VERIFIED. With a frozen source, completing every submission fanout first
     * writes the full history to each currently active follower. The remaining
     * per-follow backfill markers can then be reconciled without writing every
     * viewer/submission pair a second time.
     */
    public DrainResult drainPendingForCutover() {
        long submissionChunks = 0L;
        long followChunks = 0L;
        long idlePasses = 0L;

        while (true) {
            List<Submission> submissions = submissionRepository.findPendingFeedTimelineFanouts(
                    DRAIN_BATCH_SIZE
            );
            if (submissions.isEmpty()) {
                break;
            }

            boolean advanced = false;
            for (Submission submission : submissions) {
                if (materializeSubmissionChunk(submission.getId())) {
                    submissionChunks++;
                    advanced = true;
                }
            }
            if (advanced) {
                idlePasses = 0L;
                continue;
            }
            if (++idlePasses >= 3L) {
                throw new IllegalStateException(
                        "Durable submission timeline fanout made no progress for three passes"
                );
            }
        }

        idlePasses = 0L;
        while (true) {
            List<Follow> follows = followRepository.findPendingTimelineBackfills(DRAIN_BATCH_SIZE);
            if (follows.isEmpty()) {
                return new DrainResult(submissionChunks, followChunks);
            }

            boolean advanced = false;
            for (Follow follow : follows) {
                followRepository.updateTimelineBackfillProgress(follow.getId(), null, true);
                followChunks++;
                advanced = true;
            }
            if (advanced) {
                idlePasses = 0L;
                continue;
            }
            if (++idlePasses >= 3L) {
                throw new IllegalStateException(
                        "Durable follow timeline reconciliation made no progress for three passes"
                );
            }
        }
    }

    public boolean isCutoverReady() {
        return submissionRepository.findPendingFeedTimelineFanouts(1).isEmpty()
                && followRepository.findPendingTimelineBackfills(1).isEmpty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record DrainResult(long submissionChunks, long followChunks) { }
}
