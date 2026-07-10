package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionModerationJobTest {

    private SubmissionModerationJob job;
    private SubmissionRepository submissionRepository;
    private ContentModerationService contentModerationService;
    private RedisCacheServiceImpl cacheService;
    private LeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        job = new SubmissionModerationJob();
        submissionRepository = mock(SubmissionRepository.class);
        contentModerationService = mock(ContentModerationService.class);
        cacheService = mock(RedisCacheServiceImpl.class);
        leaderboardService = mock(LeaderboardService.class);

        ReflectionTestUtils.setField(job, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(job, "userRepository", mock(UserRepository.class));
        ReflectionTestUtils.setField(job, "moderationFlagRepository", mock(ModerationFlagRepository.class));
        ReflectionTestUtils.setField(job, "contentModerationService", contentModerationService);
        ReflectionTestUtils.setField(job, "cacheService", cacheService);
        ReflectionTestUtils.setField(job, "leaderboardService", leaderboardService);
        ReflectionTestUtils.setField(job, "authTokenStore", mock(AuthTokenStore.class));
    }

    @Test
    void moderationInfrastructureFailureHidesSubmissionAndInvalidatesDerivedState()
            throws ChallengeSphereException {
        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(7L);
        submission.setChallengeId(3L);
        submission.setActive(true);
        when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(anyList()))
                .thenThrow(new IllegalStateException("moderation unavailable"));

        assertDoesNotThrow(() -> job.moderateSubmissionAsync(
                99L,
                7L,
                3L,
                List.of("submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg")));

        assertFalse(submission.getActive());
        verify(submissionRepository).save(submission);
        verify(cacheService).invalidateAllFeeds();
        verify(cacheService).invalidateTimeline(7L);
        verify(cacheService).invalidateGloryCaches(99L);
        verify(cacheService).invalidateCommentsForSubmission(99L);
        verify(leaderboardService).rebuildLeaderboard(3L);
    }
}
