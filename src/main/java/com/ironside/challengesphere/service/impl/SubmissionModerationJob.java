package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

@Service
public class SubmissionModerationJob {

    private static final Logger logger = LogManager.getLogger(SubmissionModerationJob.class);

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModerationFlagRepository moderationFlagRepository;

    @Autowired
    private ContentModerationService contentModerationService;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private AuthTokenStore authTokenStore;

    @Async("cacheExecutor")
    @Transactional
    public void moderateSubmissionAsync(Long submissionId, Long userId, Long challengeId, List<String> mediaUrls) {
        if (submissionId == null) {
            logger.warn("Skipping async moderation without a submission id");
            return;
        }

        List<String> urls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            logger.warn("Skipping async moderation; submission {} was not found", submissionId);
            return;
        }
        if (!Boolean.TRUE.equals(submission.getActive())) {
            logger.info("Skipping async moderation; submission {} is no longer active", submissionId);
            return;
        }

        try {
            ContentModerationService.Result result = contentModerationService.scanStrict(urls);
            if (result.verdict() == ContentModerationService.Verdict.CLEAN) {
                logger.info("Async moderation passed for submission {}", submissionId);
                return;
            }

            if (result.verdict() == ContentModerationService.Verdict.FLAG_FOR_REVIEW) {
                hideSubmission(submission);
                saveModerationFlags(submission, result);
                invalidateAfterRemoval(submission);
                logger.warn("Submission {} hidden for moderation review", submissionId);
                return;
            }

            hideSubmission(submission);
            banUser(userId, result);
            invalidateAfterRemoval(submission);
            logger.warn("Submission {} hidden and user {} banned after async moderation", submissionId, userId);
        } catch (Exception e) {
            logger.error("Async moderation failed for submission {}; deleting uploaded media: {}", submissionId, e.getClass().getSimpleName());
            mediaUrlService.deleteMediaObjects(urls);
            hideSubmission(submission);
            invalidateAfterRemoval(submission);
        }
    }

    private void hideSubmission(Submission submission) {
        submission.setActive(false);
        submission.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
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

    private void banUser(Long userId, ContentModerationService.Result result) {
        if (userId == null) {
            return;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }

        user.setActive(false);
        user.setBannedAt(new Timestamp(System.currentTimeMillis()));
        user.setBanReason("Content policy violation: " + summarizeFindings(result));
        userRepository.save(user);
        authTokenStore.revokeAllForUser(userId);
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

    private static String summarizeFindings(ContentModerationService.Result result) {
        if (result.findings().isEmpty()) {
            return "unspecified";
        }

        StringBuilder sb = new StringBuilder();
        int n = Math.min(3, result.findings().size());
        for (int i = 0; i < n; i++) {
            ContentModerationService.Finding finding = result.findings().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(finding.category()).append('=').append(finding.severity());
        }
        if (result.findings().size() > n) {
            sb.append(", +").append(result.findings().size() - n).append(" more");
        }
        return sb.toString();
    }
}
