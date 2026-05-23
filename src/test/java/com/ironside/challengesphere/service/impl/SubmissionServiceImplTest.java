package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.util.MediaTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceImplTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserChallengeRepository userChallengeRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private MediaUrlService mediaUrlService;

    @Mock
    private ContentModerationService contentModerationService;

    @Mock
    private LeaderboardService leaderboardService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private SubmissionModerationJob submissionModerationJob;

    private SubmissionServiceImpl submissionService;

    @BeforeEach
    void setUp() {
        submissionService = new SubmissionServiceImpl();
        ReflectionTestUtils.setField(submissionService, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(submissionService, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(submissionService, "userRepository", userRepository);
        ReflectionTestUtils.setField(submissionService, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(submissionService, "followRepository", followRepository);
        ReflectionTestUtils.setField(submissionService, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(submissionService, "mediaUrlService", mediaUrlService);
        ReflectionTestUtils.setField(submissionService, "contentModerationService", contentModerationService);
        ReflectionTestUtils.setField(submissionService, "leaderboardService", leaderboardService);
        ReflectionTestUtils.setField(submissionService, "notificationService", notificationService);
        ReflectionTestUtils.setField(submissionService, "cacheService", cacheService);
        ReflectionTestUtils.setField(submissionService, "submissionModerationJob", submissionModerationJob);
        lenient().when(mediaUrlService.toStoredMediaUrlsString(any())).thenAnswer(invocation -> {
            List<String> mediaUrls = invocation.getArgument(0);
            return mediaUrls == null || mediaUrls.isEmpty() ? null : String.join(",", mediaUrls);
        });
    }

    @Test
    void deleteSubmissionSoftDeletesOwnedSubmission() throws Exception {
        Submission submission = submission(100L, 3L, 9L, 2, "Old proof", "old.jpg");
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        submissionService.deleteSubmission(100L, 3L);

        assertEquals(false, submission.getActive());
        verify(submissionRepository).save(submission);
    }

    @Test
    void deleteSubmissionRejectsNonOwner() {
        Submission submission = submission(100L, 3L, 9L, 2, "Old proof", "old.jpg");
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> submissionService.deleteSubmission(100L, 4L)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void uploadSubmissionMediaStoresFileThroughBackendWhenDirectDeviceUploadCannotComplete() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "proof.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3}
        );
        when(userRepository.existsById(3L)).thenReturn(true);

        String fileKey = submissionService.uploadSubmissionMedia(
                3L,
                file,
                MediaTypes.SUBMISSION_PHOTO,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG
        );

        assertTrue(fileKey.startsWith("submissions/user_3/SUBMISSION_PHOTO/"));
        assertTrue(fileKey.endsWith(".jpg"));
        verify(mediaUrlService).uploadObject(eq(fileKey), aryEq(new byte[]{1, 2, 3}), eq("image/jpeg"));
    }

    @Test
    void getSubmissionsByUserAndChallengeHidesInactiveSubmissionsForOwner() throws Exception {
        Submission active = submission(100L, 3L, 9L, 1, "Active proof", "active.jpg");
        Submission deleted = submission(101L, 3L, 9L, 2, "Deleted proof", "deleted.jpg");
        deleted.setActive(false);

        lenient().when(submissionRepository.findByUserIdAndChallengeId(eq(3L), eq(9L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(active, deleted)));
        lenient().when(submissionRepository.findActiveByUserIdAndChallengeId(3L, 9L))
                .thenReturn(List.of(active));

        SubmissionsResponse response = submissionService.getSubmissionsByUserAndChallenge(3L, 9L, 1, 100, 3L);

        assertEquals(1, response.getTotalCount());
        assertEquals(1, response.getSubmissions().size());
        assertEquals(100L, response.getSubmissions().get(0).getId());
    }

    @Test
    void getSubmissionsByUserAndChallengeShowsVisiblePublicSubmissionsForOtherUsers() throws Exception {
        Submission visible = submission(100L, 3L, 9L, 1, "Visible proof", "visible.jpg");
        Submission privateSubmission = submission(101L, 3L, 9L, 2, "Private proof", "private.jpg");
        privateSubmission.setIsPrivate(true);

        when(submissionRepository.findActiveByUserIdAndChallengeId(3L, 9L))
                .thenReturn(List.of(visible, privateSubmission));
        when(userBlockRepository.existsActiveBlockBetween(4L, 3L)).thenReturn(false);
        when(challengeRepository.findById(9L)).thenReturn(Optional.of(challenge(LocalDate.now().minusDays(1), 7)));

        SubmissionsResponse response = submissionService.getSubmissionsByUserAndChallenge(3L, 9L, 1, 100, 4L);

        assertEquals(1, response.getTotalCount());
        assertEquals(1, response.getSubmissions().size());
        assertEquals(100L, response.getSubmissions().get(0).getId());
    }

    @Test
    void getRandomChallengeSubmissionsByDayShufflesOnlyPaginatedVisibleSubmissions() throws Exception {
        Submission dayOne = submission(100L, 3L, 9L, 1, "Day one proof", "day-one.jpg");
        Submission anotherDayOne = submission(101L, 4L, 9L, 1, "Another day one proof", "day-one-2.jpg");

        when(submissionRepository.findVisibleByChallengeIdAndDayNumber(eq(9L), eq(1), eq(8L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dayOne, anotherDayOne), PageRequest.of(1, 2), 5));

        SubmissionsResponse response = submissionService.getRandomChallengeSubmissionsByDay(9L, 1, 2, 2, 8L);

        assertEquals(5, response.getTotalCount());
        assertEquals(2, response.getSubmissions().size());
        assertEquals(Set.of(100L, 101L), response.getSubmissions().stream().map(Submission::getId).collect(java.util.stream.Collectors.toSet()));
        verify(submissionRepository).findVisibleByChallengeIdAndDayNumber(eq(9L), eq(1), eq(8L), any(Pageable.class));
    }

    @Test
    void getSubmissionByIdRejectsInactiveSubmissionForOwner() {
        Submission deleted = submission(100L, 3L, 9L, 2, "Deleted proof", "deleted.jpg");
        deleted.setActive(false);
        when(submissionRepository.findById(100L)).thenReturn(Optional.of(deleted));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> submissionService.getSubmissionById(100L, 3L)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void reuploadSubmissionReplacesMediaAndCaptionWithoutChangingDay() throws Exception {
        Submission submission = submission(100L, 3L, 9L, 2, "Old proof", "old.jpg");
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(3L);
        request.setChallengeId(9L);
        request.setDayNumber(99);
        request.setDescription("New proof");
        request.setMediaUrls(List.of("new.jpg"));

        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(challengeRepository.findById(9L)).thenReturn(Optional.of(challenge(LocalDate.now().minusDays(1), 7)));
        when(contentModerationService.scan(List.of("new.jpg"))).thenReturn(new ContentModerationService.Result(
                ContentModerationService.Verdict.CLEAN,
                List.of()
        ));
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubmissionResponse response = submissionService.reuploadSubmission(100L, request);

        assertEquals(true, response.isSuccess());
        assertEquals(2, response.getDayNumber());
        assertEquals("New proof", submission.getDescription());
        assertEquals("new.jpg", submission.getMediaUrls());
        assertEquals(true, submission.getActive());
        verify(mediaUrlService).applyPrivateImageCacheHeaders(List.of("new.jpg"));
    }

    @Test
    void reuploadSubmissionRejectsUploadsAfterTwoDayGraceWindow() {
        Submission submission = submission(100L, 3L, 9L, 1, "Old proof", "old.jpg");
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(3L);
        request.setChallengeId(9L);
        request.setDayNumber(1);
        request.setDescription("New proof");
        request.setMediaUrls(List.of("new.jpg"));

        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        lenient().when(challengeRepository.findById(9L)).thenReturn(Optional.of(challenge(LocalDate.now().minusDays(3), 7)));
        lenient().when(contentModerationService.scan(List.of("new.jpg"))).thenReturn(new ContentModerationService.Result(
                ContentModerationService.Verdict.CLEAN,
                List.of()
        ));
        lenient().when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> submissionService.reuploadSubmission(100L, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void createSubmissionRejectsUploadsAfterTwoDayGraceWindow() {
        CreateSubmissionRequest request = createSubmissionRequest(3L, 9L, 1, "Late proof", "late.jpg");

        stubCreateSubmissionDependencies(challenge(LocalDate.now().minusDays(3), 7), List.of());

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> submissionService.createSubmission(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void createSubmissionAllowsDeletedDayUploadWithinTwoDayGraceWindow() throws Exception {
        CreateSubmissionRequest request = createSubmissionRequest(3L, 9L, 1, "Replacement proof", "replacement.jpg");
        List<Submission> deletedSubmissions = IntStream.range(0, 5)
                .mapToObj(i -> {
                    Submission deleted = submission(200L + i, 3L, 9L, 1, "Deleted proof", "deleted-" + i + ".jpg");
                    deleted.setActive(false);
                    return deleted;
                })
                .toList();

        stubCreateSubmissionDependencies(challenge(LocalDate.now().minusDays(1), 7), deletedSubmissions);
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission saved = invocation.getArgument(0);
            saved.setId(300L);
            return saved;
        });

        SubmissionResponse response = submissionService.createSubmission(request);

        assertEquals(true, response.isSuccess());
        assertEquals(300L, response.getSubmissionId());
        verify(mediaUrlService).applyPrivateImageCacheHeaders(List.of("replacement.jpg"));
    }

    @Test
    void createSubmissionSavesImmediatelyAndEnqueuesAsyncModeration() throws Exception {
        CreateSubmissionRequest request = createSubmissionRequest(3L, 9L, 1, "Optimistic proof", "optimistic.jpg");
        stubCreateSubmissionDependencies(challenge(LocalDate.now().minusDays(1), 7), List.of());
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission saved = invocation.getArgument(0);
            saved.setId(301L);
            return saved;
        });

        SubmissionResponse response = submissionService.createSubmission(request);

        ArgumentCaptor<Submission> submissionCaptor = ArgumentCaptor.forClass(Submission.class);
        assertEquals(true, response.isSuccess());
        assertEquals(301L, response.getSubmissionId());
        verify(submissionRepository).save(submissionCaptor.capture());
        assertEquals(true, submissionCaptor.getValue().getActive());
        verify(contentModerationService, never()).scan(any());
        verify(submissionModerationJob).moderateSubmissionAsync(301L, 3L, 9L, List.of("optimistic.jpg"));
    }

    @Test
    void createSubmissionAllowsOneOffAnytimeAndCompletesWithoutLeaderboard() throws Exception {
        CreateSubmissionRequest request = createSubmissionRequest(3L, 9L, 1, "Finished it", "done.jpg");
        Challenge oneOff = challenge(LocalDate.now().minusDays(30), 1);
        oneOff.setChallengeMode("ONE_OFF");

        User user = new User();
        user.setId(3L);
        user.setActive(true);

        UserChallenge userChallenge = new UserChallenge();
        userChallenge.setUserId(3L);
        userChallenge.setChallengeId(9L);
        userChallenge.setActive(true);
        userChallenge.setCompleted(false);

        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(challengeRepository.findById(9L)).thenReturn(Optional.of(oneOff));
        when(userChallengeRepository.findByUserIdAndChallengeId(3L, 9L)).thenReturn(Optional.of(userChallenge));
        when(submissionRepository.findByUserChallengeAndDay(3L, 9L, 1)).thenReturn(List.of());
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission saved = invocation.getArgument(0);
            saved.setId(303L);
            return saved;
        });
        lenient().when(followRepository.findActiveFollowsByFollowingId(3L)).thenReturn(List.of());

        SubmissionResponse response = submissionService.createSubmission(request);

        assertEquals(true, response.isSuccess());
        assertEquals(303L, response.getSubmissionId());
        verify(userChallengeRepository).save(argThat(savedLink ->
                Boolean.TRUE.equals(savedLink.getCompleted())
                        && savedLink.getCompletedAt() != null
        ));
        verify(leaderboardService, never()).incrementScore(any(), any(), anyDouble(), any());
    }

    @Test
    void createSubmissionRejectsSecondActiveOneOffSubmission() {
        CreateSubmissionRequest request = createSubmissionRequest(3L, 9L, 1, "Again", "again.jpg");
        Challenge oneOff = challenge(LocalDate.now().minusDays(30), 1);
        oneOff.setChallengeMode("ONE_OFF");
        Submission existing = submission(100L, 3L, 9L, 1, "Existing", "existing.jpg");

        stubCreateSubmissionDependencies(oneOff, List.of(existing));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> submissionService.createSubmission(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(submissionRepository, never()).save(any(Submission.class));
        verify(leaderboardService, never()).incrementScore(any(), any(), anyDouble(), any());
    }

    @Test
    void createSubmissionStoresGcsObjectKeysForMultiPhotoUploads() throws Exception {
        MediaUrlService realMediaUrlService = spy(new MediaUrlService());
        ReflectionTestUtils.setField(realMediaUrlService, "bucketName", "challengesphere-media");
        doNothing().when(realMediaUrlService).applyPrivateImageCacheHeaders(any());
        ReflectionTestUtils.setField(submissionService, "mediaUrlService", realMediaUrlService);

        List<String> uploadedUrls = List.of(
                "https://storage.googleapis.com/challengesphere-media/submissions/user_3/IMAGE/1710000000000_first.jpg",
                "https://storage.googleapis.com/challengesphere-media/submissions/user_3/IMAGE/1710000000001_second.jpg",
                "https://storage.googleapis.com/challengesphere-media/submissions/user_3/IMAGE/1710000000002_third.jpg",
                "https://storage.googleapis.com/challengesphere-media/submissions/user_3/IMAGE/1710000000003_fourth.jpg"
        );
        CreateSubmissionRequest request = createSubmissionRequest(3L, 9L, 1, "Multi photo proof", uploadedUrls);
        stubCreateSubmissionDependencies(challenge(LocalDate.now().minusDays(1), 7), List.of());
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission saved = invocation.getArgument(0);
            saved.setId(302L);
            return saved;
        });

        submissionService.createSubmission(request);

        ArgumentCaptor<Submission> submissionCaptor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(submissionCaptor.capture());
        assertEquals(
                "submissions/user_3/IMAGE/1710000000000_first.jpg,"
                        + "submissions/user_3/IMAGE/1710000000001_second.jpg,"
                        + "submissions/user_3/IMAGE/1710000000002_third.jpg,"
                        + "submissions/user_3/IMAGE/1710000000003_fourth.jpg",
                submissionCaptor.getValue().getMediaUrls()
        );
    }

    private CreateSubmissionRequest createSubmissionRequest(Long userId, Long challengeId, Integer dayNumber, String description, String mediaUrl) {
        return createSubmissionRequest(userId, challengeId, dayNumber, description, List.of(mediaUrl));
    }

    private CreateSubmissionRequest createSubmissionRequest(Long userId, Long challengeId, Integer dayNumber, String description, List<String> mediaUrls) {
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(userId);
        request.setChallengeId(challengeId);
        request.setDayNumber(dayNumber);
        request.setDescription(description);
        request.setMediaUrls(mediaUrls);
        return request;
    }

    private void stubCreateSubmissionDependencies(Challenge challenge, List<Submission> existingSubmissions) {
        User user = new User();
        user.setId(3L);
        user.setActive(true);

        UserChallenge userChallenge = new UserChallenge();
        userChallenge.setUserId(3L);
        userChallenge.setChallengeId(9L);
        userChallenge.setActive(true);

        lenient().when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        lenient().when(challengeRepository.findById(9L)).thenReturn(Optional.of(challenge));
        lenient().when(userChallengeRepository.findByUserIdAndChallengeId(3L, 9L)).thenReturn(Optional.of(userChallenge));
        lenient().when(submissionRepository.findByUserChallengeAndDay(3L, 9L, 1)).thenReturn(existingSubmissions);
        lenient().when(contentModerationService.scan(any())).thenReturn(new ContentModerationService.Result(
                ContentModerationService.Verdict.CLEAN,
                List.of()
        ));
        lenient().when(followRepository.findActiveFollowsByFollowingId(3L)).thenReturn(List.of());
    }

    private Submission submission(Long id, Long userId, Long challengeId, Integer dayNumber, String description, String mediaUrls) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setUserId(userId);
        submission.setChallengeId(challengeId);
        submission.setDayNumber(dayNumber);
        submission.setDescription(description);
        submission.setMediaUrls(mediaUrls);
        submission.setSubmissionTime(Timestamp.from(Instant.parse("2026-05-16T00:00:00Z")));
        submission.setCreatedAt(Timestamp.from(Instant.parse("2026-05-16T00:00:00Z")));
        submission.setActive(true);
        submission.setIsPrivate(false);
        submission.setGloriesCount(0L);
        submission.setCommentsCount(0L);
        submission.setVersion(0);
        return submission;
    }

    private Challenge challenge(LocalDate startDate, Integer numberOfDays) {
        Challenge challenge = new Challenge();
        challenge.setId(9L);
        challenge.setTitle("Daily Pushups");
        challenge.setStartDate(startDate);
        challenge.setNumberOfDays(numberOfDays);
        challenge.setActive(true);
        challenge.setIsPrivate(false);
        challenge.setCreatorId(3L);
        challenge.setCurrentOwnerId(3L);
        return challenge;
    }
}
