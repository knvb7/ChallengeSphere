package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SubmissionMediaSecurityIntegrationTest {

    private static final String FOREIGN_KEY =
            "submissions/user_8/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
    private static final String OWNED_KEY =
            "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
    private static final String OLD_OWNED_KEY =
            "submissions/user_7/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174001.png";

    private SubmissionServiceImpl service;
    private SubmissionRepository submissionRepository;
    private ChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private UserChallengeRepository userChallengeRepository;
    private FollowRepository followRepository;
    private LeaderboardService leaderboardService;
    private RedisCacheServiceImpl cacheService;
    private NotificationService notificationService;
    private UserBlockRepository userBlockRepository;
    private MediaUrlService mediaUrlService;
    private ContentModerationService contentModerationService;
    private SubmissionModerationJob submissionModerationJob;

    @BeforeEach
    void setUp() {
        service = new SubmissionServiceImpl();
        submissionRepository = mock(SubmissionRepository.class);
        challengeRepository = mock(ChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        followRepository = mock(FollowRepository.class);
        leaderboardService = mock(LeaderboardService.class);
        cacheService = mock(RedisCacheServiceImpl.class);
        notificationService = mock(NotificationService.class);
        userBlockRepository = mock(UserBlockRepository.class);
        mediaUrlService = mock(MediaUrlService.class);
        contentModerationService = mock(ContentModerationService.class);
        submissionModerationJob = mock(SubmissionModerationJob.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", new SubmissionMediaKeyPolicy());
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "followRepository", followRepository);
        ReflectionTestUtils.setField(service, "leaderboardService", leaderboardService);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(service, "mediaUrlService", mediaUrlService);
        ReflectionTestUtils.setField(service, "contentModerationService", contentModerationService);
        ReflectionTestUtils.setField(service, "submissionModerationJob", submissionModerationJob);
    }

    @Test
    void createRejectsForeignMediaBeforeAnySideEffect() {
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setChallengeId(3L);
        request.setDayNumber(1);
        request.setDescription("proof");
        request.setMediaUrls(List.of(FOREIGN_KEY));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createSubmission(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(submissionRepository, mediaUrlService);
    }

    @Test
    void reuploadRejectsForeignMediaBeforeAnySideEffect() {
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setDescription("replacement");
        request.setMediaUrls(List.of(FOREIGN_KEY));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.reuploadSubmission(99L, request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(submissionRepository, mediaUrlService);
    }

    @Test
    void reuploadStrictModerationFailurePreservesExistingSubmissionAndMedia() {
        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(7L);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);
        submission.setDescription("original proof");
        submission.setMediaUrls(OLD_OWNED_KEY);
        submission.setIsPrivate(false);
        submission.setActive(true);

        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setActive(true);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(30);
        challenge.setChallengeMode(Challenge.MODE_DAILY);

        when(submissionRepository.findById(99L)).thenReturn(Optional.of(submission));
        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge));
        when(contentModerationService.scanStrict(List.of(OWNED_KEY)))
                .thenThrow(new IllegalStateException("moderation unavailable"));

        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setDescription("replacement proof");
        request.setMediaUrls(List.of(OWNED_KEY));
        request.setIsPrivate(true);

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.reuploadSubmission(99L, request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatus());
        assertEquals("Failed to reupload submission", error.getResponseMessage());
        assertEquals("original proof", submission.getDescription());
        assertEquals(OLD_OWNED_KEY, submission.getMediaUrls());
        assertEquals(false, submission.getIsPrivate());
        assertTrue(submission.getActive());
        verify(contentModerationService).scanStrict(List.of(OWNED_KEY));
        verify(contentModerationService, never()).scan(anyList());
        verify(submissionRepository, never()).save(any(Submission.class));
    }

    @Test
    void moderationDispatchWaitsForCommitAndPassesOnlySubmissionId() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            ReflectionTestUtils.invokeMethod(service, "scheduleSubmissionModerationAfterCommit", 99L);

            verifyNoInteractions(submissionModerationJob);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.get(0).afterCommit();

            verify(submissionModerationJob).moderateSubmissionAsync(99L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publicCreateSubmissionSchedulesModerationOnlyAfterCommit() throws ChallengeSphereException {
        User user = new User();
        user.setId(7L);
        user.setActive(true);

        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setActive(true);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(30);
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        challenge.setIsPrivate(false);

        UserChallenge userChallenge = new UserChallenge();
        userChallenge.setUserId(7L);
        userChallenge.setChallengeId(3L);
        userChallenge.setActive(true);

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 3L))
                .thenReturn(Optional.of(userChallenge));
        when(submissionRepository.findByUserChallengeAndDay(7L, 3L, 1)).thenReturn(List.of());
        when(mediaUrlService.toStoredMediaUrlsString(List.of())).thenReturn(null);
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(followRepository.findActiveFollowsByFollowingId(7L)).thenReturn(List.of());

        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setChallengeId(3L);
        request.setDayNumber(1);
        request.setDescription("text-only proof");
        request.setMediaUrls(List.of());
        request.setIsPrivate(false);

        TransactionSynchronizationManager.initSynchronization();
        try {
            SubmissionResponse response = service.createSubmission(request);

            assertTrue(response.isSuccess());
            assertEquals(99L, response.getSubmissionId());
            verifyNoInteractions(submissionModerationJob);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(submissionModerationJob).moderateSubmissionAsync(99L);
            verifyNoMoreInteractions(submissionModerationJob);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void moderationDispatchRunsImmediatelyWithoutTransactionSynchronization() {
        ReflectionTestUtils.invokeMethod(service, "scheduleSubmissionModerationAfterCommit", 99L);

        verify(submissionModerationJob).moderateSubmissionAsync(99L);
    }

    @Test
    void generatePresignedUrlsDelegatesKeyCreationToPolicy() throws Exception {
        SubmissionMediaKeyPolicy keyPolicy = mock(SubmissionMediaKeyPolicy.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", keyPolicy);
        when(userRepository.existsById(7L)).thenReturn(true);

        GeneratePresignedUrlsRequest.MediaFileInfo mediaFile = jpegMediaFile();
        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        request.setUserId(7L);
        request.setMediaFiles(List.of(mediaFile));
        when(keyPolicy.createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class)))
                .thenThrow(new ChallengeSphereException("stop before signing", HttpStatus.BAD_REQUEST, null));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generatePresignedUrls(request));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatus());
        assertEquals("Failed to generate presigned URL for file: proof.jpg", error.getResponseMessage());

        verify(keyPolicy).createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class));
        verifyNoInteractions(mediaUrlService);
    }

    @Test
    void uploadSubmissionMediaDelegatesKeyCreationToPolicy() throws Exception {
        SubmissionMediaKeyPolicy keyPolicy = mock(SubmissionMediaKeyPolicy.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", keyPolicy);
        when(userRepository.existsById(7L)).thenReturn(true);
        when(keyPolicy.createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class)))
                .thenThrow(new ChallengeSphereException("stop before upload", HttpStatus.BAD_REQUEST, null));

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", "image/jpeg", new byte[]{1});
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.uploadSubmissionMedia(
                        7L,
                        file,
                        MediaTypes.SUBMISSION_PHOTO,
                        GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("stop before upload", error.getResponseMessage());

        verify(keyPolicy).createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class));
        verifyNoInteractions(mediaUrlService);
    }

    private GeneratePresignedUrlsRequest.MediaFileInfo jpegMediaFile() {
        GeneratePresignedUrlsRequest.MediaFileInfo mediaFile =
                new GeneratePresignedUrlsRequest.MediaFileInfo();
        mediaFile.setFileName("proof.jpg");
        mediaFile.setMediaType(MediaTypes.SUBMISSION_PHOTO);
        mediaFile.setContentType(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG);
        return mediaFile;
    }
}
