package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

@Service
public class SubmissionModerationJob {

    private static final Logger logger = LogManager.getLogger(SubmissionModerationJob.class);

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private ModerationFlagRepository moderationFlagRepository;

    @Autowired
    private ContentModerationService contentModerationService;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private ModerationEnforcementService moderationEnforcementService;

    @Async("moderationExecutor")
    @Transactional
    public void moderateSubmissionAsync(Long submissionId) {
        moderateIfPending(submissionId);
    }

    /**
     * Atomically claims and moderates one queued submission. The conditional
     * update makes concurrent application instances safe: only one can process
     * a given row, while stale claims are eligible again after 30 minutes.
     */
    @Transactional
    public boolean moderateIfPending(Long submissionId) {
        if (submissionId == null) {
            logger.warn("Skipping moderation without a submission id");
            return false;
        }
        if (submissionRepository.claimForModeration(submissionId) != 1) {
            return false;
        }

        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            logger.warn("Skipping moderation; claimed submission {} was not found", submissionId);
            return false;
        }

        ContentModerationService.Result result;
        try {
            result = contentModerationService.scanStrict(storedMediaUrls(submission.getMediaUrls()));
        } catch (Exception e) {
            logger.error(
                    "Moderation failed for submission {}; it will be retried: {}",
                    submission.getId(),
                    e.getClass().getSimpleName());
            markFailed(submission, e);
            invalidateAfterRemoval(submission);
            return false;
        }

        if (result.verdict() == ContentModerationService.Verdict.CLEAN) {
            markPassed(submission);
            logger.info("Moderation passed for submission {}", submission.getId());
            return true;
        }

        markFlagged(submission);
        saveModerationFlags(submission, result);
        try {
            moderationEnforcementService.removeFlaggedMediaAndWarn(submission.getUserId(), result);
        } catch (ChallengeSphereException e) {
            throw new IllegalStateException("Moderation enforcement failed", e);
        }
        invalidateAfterRemoval(submission);
        logger.warn(
                "Submission {} flagged and hidden; private media retained and user {} warned",
                submission.getId(),
                submission.getUserId());
        return true;
    }

    private static List<String> storedMediaUrls(String mediaUrls) {
        if (mediaUrls == null || mediaUrls.isBlank()) {
            return List.of();
        }

        return Arrays.stream(mediaUrls.split(","))
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .toList();
    }

    private void markPassed(Submission submission) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        submission.setModerationStatus(Submission.MODERATION_PASSED);
        submission.setModerationStartedAt(null);
        submission.setModeratedAt(now);
        submission.setModerationError(null);
        submission.setUpdatedAt(now);
        submissionRepository.save(submission);
    }

    private void markFlagged(Submission submission) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        submission.setActive(false);
        submission.setModerationStatus(Submission.MODERATION_FLAGGED);
        submission.setModerationStartedAt(null);
        submission.setModeratedAt(now);
        submission.setModerationError(null);
        submission.setUpdatedAt(now);
        submissionRepository.save(submission);
    }

    private void markFailed(Submission submission, Exception error) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        submission.setActive(false);
        submission.setModerationStatus(Submission.MODERATION_FAILED);
        submission.setModerationStartedAt(null);
        submission.setModerationError(truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 512));
        submission.setUpdatedAt(now);
        submissionRepository.save(submission);
    }

    private void saveModerationFlags(Submission submission, ContentModerationService.Result result) {
        for (ContentModerationService.Finding finding : result.findings()) {
            ModerationFlag flag = new ModerationFlag();
            flag.setSubmissionId(submission.getId());
            flag.setUserId(submission.getUserId());
            flag.setCategory(finding.category());
            flag.setSeverity(finding.severity());
            flag.setMediaUrl(finding.mediaUrl());
            flag.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            moderationFlagRepository.save(flag);
        }
    }

    private void invalidateAfterRemoval(Submission submission) {
        try {
            cacheService.invalidateAllFeeds();
            cacheService.invalidateTimeline(submission.getUserId());
            cacheService.invalidateGloryCaches(submission.getId());
            cacheService.invalidateCommentsForSubmission(submission.getId());
        } catch (Exception e) {
            logger.warn("Failed to invalidate caches for moderated submission {}: {}", submission.getId(), e.getClass().getSimpleName());
        }

        try {
            leaderboardService.rebuildLeaderboard(submission.getChallengeId());
        } catch (Exception e) {
            logger.warn("Failed to rebuild leaderboard after moderating submission {}: {}", submission.getId(), e.getClass().getSimpleName());
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
