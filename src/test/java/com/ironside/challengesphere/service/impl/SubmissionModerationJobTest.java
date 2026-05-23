package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionModerationJobTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ModerationFlagRepository moderationFlagRepository;

    @Mock
    private ContentModerationService contentModerationService;

    @Mock
    private MediaUrlService mediaUrlService;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private LeaderboardService leaderboardService;

    private SubmissionModerationJob job;

    @BeforeEach
    void setUp() {
        job = new SubmissionModerationJob();
        ReflectionTestUtils.setField(job, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(job, "userRepository", userRepository);
        ReflectionTestUtils.setField(job, "moderationFlagRepository", moderationFlagRepository);
        ReflectionTestUtils.setField(job, "contentModerationService", contentModerationService);
        ReflectionTestUtils.setField(job, "mediaUrlService", mediaUrlService);
        ReflectionTestUtils.setField(job, "cacheService", cacheService);
        ReflectionTestUtils.setField(job, "leaderboardService", leaderboardService);
    }

    @Test
    void cleanScanLeavesOptimisticSubmissionActive() {
        Submission submission = submission();
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of("clean.jpg"))).thenReturn(new ContentModerationService.Result(
                ContentModerationService.Verdict.CLEAN,
                List.of()
        ));

        job.moderateSubmissionAsync(100L, 3L, 9L, List.of("clean.jpg"));

        assertEquals(true, submission.getActive());
        verify(mediaUrlService, never()).deleteMediaObjects(any());
        verify(moderationFlagRepository, never()).save(any());
    }

    @Test
    void visionFailureDeletesUploadedMediaAndHidesSubmission() throws Exception {
        Submission submission = submission();
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of("broken.jpg"))).thenThrow(new RuntimeException("vision down"));

        job.moderateSubmissionAsync(100L, 3L, 9L, List.of("broken.jpg"));

        assertEquals(false, submission.getActive());
        verify(mediaUrlService).deleteMediaObjects(List.of("broken.jpg"));
        verify(submissionRepository).save(submission);
        verify(leaderboardService).rebuildLeaderboard(9L);
        verify(cacheService).invalidateAllFeeds();
        verify(cacheService).invalidateTimeline(3L);
    }

    @Test
    void likelyVerdictHidesSubmissionAndCreatesModerationFlagWithoutDeletingMedia() throws Exception {
        Submission submission = submission();
        ContentModerationService.Finding finding = new ContentModerationService.Finding("review.jpg", "RACY", "LIKELY");
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(contentModerationService.scanStrict(List.of("review.jpg"))).thenReturn(new ContentModerationService.Result(
                ContentModerationService.Verdict.FLAG_FOR_REVIEW,
                List.of(finding)
        ));

        job.moderateSubmissionAsync(100L, 3L, 9L, List.of("review.jpg"));

        ArgumentCaptor<ModerationFlag> flagCaptor = ArgumentCaptor.forClass(ModerationFlag.class);
        assertEquals(false, submission.getActive());
        verify(moderationFlagRepository).save(flagCaptor.capture());
        assertEquals(100L, flagCaptor.getValue().getSubmissionId());
        assertEquals(3L, flagCaptor.getValue().getUserId());
        assertEquals("RACY", flagCaptor.getValue().getCategory());
        assertEquals("LIKELY", flagCaptor.getValue().getSeverity());
        verify(mediaUrlService, never()).deleteMediaObjects(any());
        verify(leaderboardService).rebuildLeaderboard(9L);
    }

    @Test
    void veryLikelyVerdictHidesSubmissionAndBansUser() throws Exception {
        Submission submission = submission();
        User user = new User();
        user.setId(3L);
        user.setActive(true);
        ContentModerationService.Finding finding = new ContentModerationService.Finding("blocked.jpg", "ADULT", "VERY_LIKELY");
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(contentModerationService.scanStrict(List.of("blocked.jpg"))).thenReturn(new ContentModerationService.Result(
                ContentModerationService.Verdict.BLOCK_AND_BAN,
                List.of(finding)
        ));

        job.moderateSubmissionAsync(100L, 3L, 9L, List.of("blocked.jpg"));

        assertEquals(false, submission.getActive());
        assertEquals(false, user.getActive());
        verify(userRepository).save(user);
        verify(mediaUrlService, never()).deleteMediaObjects(any());
        verify(leaderboardService).rebuildLeaderboard(9L);
    }

    private Submission submission() {
        Submission submission = new Submission();
        submission.setId(100L);
        submission.setUserId(3L);
        submission.setChallengeId(9L);
        submission.setDayNumber(1);
        submission.setDescription("Proof");
        submission.setMediaUrls("proof.jpg");
        submission.setActive(true);
        submission.setIsPrivate(false);
        submission.setSubmissionTime(Timestamp.from(Instant.parse("2026-05-16T00:00:00Z")));
        submission.setCreatedAt(Timestamp.from(Instant.parse("2026-05-16T00:00:00Z")));
        submission.setGloriesCount(0L);
        submission.setCommentsCount(0L);
        submission.setVersion(0);
        return submission;
    }
}
