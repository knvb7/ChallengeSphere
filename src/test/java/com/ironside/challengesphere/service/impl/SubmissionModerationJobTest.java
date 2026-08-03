package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SubmissionModerationJobTest {

    private static final String PHOTO =
            "submissions/user_71/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
    private static final String VIDEO =
            "submissions/user_71/SUBMISSION_VIDEO/1752148800000_123e4567-e89b-12d3-a456-426614174001.mp4";

    private SubmissionModerationJob job;
    private SubmissionRepository submissionRepository;
    private ModerationFlagRepository moderationFlagRepository;
    private ContentModerationService contentModerationService;
    private RedisCacheServiceImpl cacheService;
    private LeaderboardService leaderboardService;
    private ModerationEnforcementService moderationEnforcementService;

    @BeforeEach
    void setUp() {
        job = new SubmissionModerationJob();
        submissionRepository = mock(SubmissionRepository.class);
        moderationFlagRepository = mock(ModerationFlagRepository.class);
        contentModerationService = mock(ContentModerationService.class);
        cacheService = mock(RedisCacheServiceImpl.class);
        leaderboardService = mock(LeaderboardService.class);
        moderationEnforcementService = mock(ModerationEnforcementService.class);

        ReflectionTestUtils.setField(job, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(job, "moderationFlagRepository", moderationFlagRepository);
        ReflectionTestUtils.setField(job, "contentModerationService", contentModerationService);
        ReflectionTestUtils.setField(job, "cacheService", cacheService);
        ReflectionTestUtils.setField(job, "leaderboardService", leaderboardService);
        ReflectionTestUtils.setField(job, "moderationEnforcementService", moderationEnforcementService);
        when(submissionRepository.claimForModeration(anyLong())).thenReturn(1);
    }

    @Test
    void moderationUsesTheDedicatedNonBlockingExecutor() throws NoSuchMethodException {
        Async async = SubmissionModerationJob.class
                .getMethod("moderateSubmissionAsync", Long.class)
                .getAnnotation(Async.class);

        assertNotNull(async);
        assertEquals("moderationExecutor", async.value());
    }

    @Test
    void moderationInfrastructureFailureHidesSubmissionAndInvalidatesDerivedState()
            throws ChallengeSphereException {
        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(71L);
        submission.setChallengeId(31L);
        submission.setMediaUrls(PHOTO + "," + VIDEO);
        submission.setActive(true);
        when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of(PHOTO, VIDEO)))
                .thenThrow(new IllegalStateException("moderation unavailable"));

        assertDoesNotThrow(() -> job.moderateSubmissionAsync(99L));

        assertFalse(submission.getActive());
        verify(contentModerationService).scanStrict(List.of(PHOTO, VIDEO));
        verify(submissionRepository).save(submission);
        verify(cacheService).invalidateAllFeeds();
        verify(cacheService).invalidateTimeline(71L);
        verify(cacheService).invalidateGloryCaches(99L);
        verify(cacheService).invalidateCommentsForSubmission(99L);
        verify(leaderboardService).rebuildLeaderboard(31L);
        assertEquals(Submission.MODERATION_FAILED, submission.getModerationStatus());
    }

    @Test
    void cleanSubmissionIsMarkedPassedAndWillNotBeQueuedAgain() {
        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(71L);
        submission.setChallengeId(31L);
        submission.setMediaUrls(PHOTO);
        submission.setActive(true);
        when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of(PHOTO))).thenReturn(
                new ContentModerationService.Result(ContentModerationService.Verdict.CLEAN, List.of()));

        assertTrue(job.moderateIfPending(99L));

        assertEquals(Submission.MODERATION_PASSED, submission.getModerationStatus());
        assertNotNull(submission.getModeratedAt());
        verify(submissionRepository).save(submission);
        verifyNoInteractions(moderationFlagRepository, moderationEnforcementService);
    }

    @Test
    void alreadyClaimedOrCompletedSubmissionIsNotScannedAgain() {
        when(submissionRepository.claimForModeration(99L)).thenReturn(0);

        assertFalse(job.moderateIfPending(99L));

        verify(submissionRepository).claimForModeration(99L);
        verifyNoInteractions(contentModerationService, moderationFlagRepository, moderationEnforcementService);
    }

    @Test
    void flagForReviewUsesLoadedRowIdentifiersAndCanonicalMedia() throws ChallengeSphereException {
        long requestedSubmissionId = 900L;
        long loadedSubmissionId = 99L;
        long loadedUserId = 71L;
        long loadedChallengeId = 31L;
        Submission submission = new Submission();
        submission.setId(loadedSubmissionId);
        submission.setUserId(loadedUserId);
        submission.setChallengeId(loadedChallengeId);
        submission.setMediaUrls(PHOTO + "," + VIDEO);
        submission.setActive(true);

        ContentModerationService.Result result = new ContentModerationService.Result(
                ContentModerationService.Verdict.FLAG_FOR_REVIEW,
                List.of(
                        new ContentModerationService.Finding(PHOTO, "Racy", "LIKELY"),
                        new ContentModerationService.Finding(VIDEO, "Violence", "LIKELY")));
        when(submissionRepository.findById(requestedSubmissionId)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of(PHOTO, VIDEO))).thenReturn(result);

        assertDoesNotThrow(() -> job.moderateSubmissionAsync(requestedSubmissionId));

        assertFalse(submission.getActive());
        verify(contentModerationService).scanStrict(List.of(PHOTO, VIDEO));
        verify(submissionRepository).save(submission);

        ArgumentCaptor<ModerationFlag> flagCaptor = ArgumentCaptor.forClass(ModerationFlag.class);
        verify(moderationFlagRepository, times(result.findings().size())).save(flagCaptor.capture());
        List<ModerationFlag> savedFlags = flagCaptor.getAllValues();
        assertEquals(List.of(loadedSubmissionId, loadedSubmissionId),
                savedFlags.stream().map(ModerationFlag::getSubmissionId).toList());
        assertEquals(List.of(loadedUserId, loadedUserId),
                savedFlags.stream().map(ModerationFlag::getUserId).toList());
        assertEquals(result.findings().stream().map(ContentModerationService.Finding::mediaUrl).toList(),
                savedFlags.stream().map(ModerationFlag::getMediaUrl).toList());
        verifyNoMoreInteractions(moderationFlagRepository);

        verify(cacheService).invalidateAllFeeds();
        verify(cacheService).invalidateTimeline(loadedUserId);
        verify(cacheService).invalidateGloryCaches(loadedSubmissionId);
        verify(cacheService).invalidateCommentsForSubmission(loadedSubmissionId);
        verifyNoMoreInteractions(cacheService);
        verify(leaderboardService).rebuildLeaderboard(loadedChallengeId);
        verifyNoMoreInteractions(leaderboardService);
        verify(moderationEnforcementService).removeFlaggedMediaAndWarn(loadedUserId, result);
    }

    @Test
    void blockRemovesMediaAndWarnsPersistedSubmissionOwner() throws ChallengeSphereException {
        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(71L);
        submission.setChallengeId(31L);
        submission.setMediaUrls(PHOTO + "," + VIDEO);
        submission.setActive(true);

        ContentModerationService.Result result = new ContentModerationService.Result(
                ContentModerationService.Verdict.BLOCK_AND_BAN,
                List.of(new ContentModerationService.Finding(PHOTO, "Explicit Nudity", "VERY_LIKELY")));
        when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of(PHOTO, VIDEO))).thenReturn(result);

        assertDoesNotThrow(() -> job.moderateSubmissionAsync(99L));

        assertFalse(submission.getActive());
        verify(contentModerationService).scanStrict(List.of(PHOTO, VIDEO));
        verify(submissionRepository).save(submission);
        verify(moderationEnforcementService).removeFlaggedMediaAndWarn(71L, result);
        verify(cacheService).invalidateTimeline(71L);
        verify(leaderboardService).rebuildLeaderboard(31L);
    }
}
