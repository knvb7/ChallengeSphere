package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.MyChallengesResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChallengeServiceImplTest {

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private UserChallengeRepository userChallengeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private MediaUrlService mediaUrlService;

    private ChallengeServiceImpl challengeService;

    @BeforeEach
    void setUp() {
        challengeService = new ChallengeServiceImpl();
        ReflectionTestUtils.setField(challengeService, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(challengeService, "userRepository", userRepository);
        ReflectionTestUtils.setField(challengeService, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(challengeService, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(challengeService, "followRepository", followRepository);
        ReflectionTestUtils.setField(challengeService, "notificationService", notificationService);
        ReflectionTestUtils.setField(challengeService, "cacheService", cacheService);
        ReflectionTestUtils.setField(challengeService, "mediaUrlService", mediaUrlService);
        ReflectionTestUtils.setField(challengeService, "bucketName", "challenge-bucket");
    }

    @Test
    void createChallengePersistsMediaUrlsAsCoverPhotoUrls() throws Exception {
        ChallengeCreateRequest request = new ChallengeCreateRequest();
        request.setTitle("Daily Run");
        request.setDescription("Run every day");
        request.setCreatorUserId(7L);
        request.setStartDate(LocalDate.of(2026, 5, 13));
        request.setNumberOfDays(7);
        request.setMediaUrls(List.of(
                "challenges/user_7/covers/run.jpg",
                "https://cdn.example.com/cover.png"
        ));

        when(userRepository.existsById(7L)).thenReturn(true);
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> {
            Challenge challenge = invocation.getArgument(0);
            challenge.setId(99L);
            return challenge;
        });
        when(mediaUrlService.toReadableMediaUrls(
                "https://storage.googleapis.com/challenge-bucket/challenges/user_7/covers/run.jpg,https://cdn.example.com/cover.png"
        )).thenReturn(
                "https://signed.example.com/run.jpg,https://cdn.example.com/cover.png"
        );

        ChallengeCreateResponse response = challengeService.createChallenge(request);

        assertEquals(List.of(
                "https://signed.example.com/run.jpg",
                "https://cdn.example.com/cover.png"
        ), response.getCoverPhotoUrls());
        verify(challengeRepository).save(argThat(challenge ->
                "https://storage.googleapis.com/challenge-bucket/challenges/user_7/covers/run.jpg,https://cdn.example.com/cover.png"
                        .equals(challenge.getCoverPhotoUrls())
        ));
    }

    @Test
    void createChallengeRegistersCreatorAsActiveParticipant() throws Exception {
        ChallengeCreateRequest request = new ChallengeCreateRequest();
        request.setTitle("Daily Run");
        request.setDescription("Run every day");
        request.setCreatorUserId(7L);
        request.setStartDate(LocalDate.of(2026, 5, 16));
        request.setNumberOfDays(7);

        when(userRepository.existsById(7L)).thenReturn(true);
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> {
            Challenge challenge = invocation.getArgument(0);
            challenge.setId(99L);
            return challenge;
        });

        challengeService.createChallenge(request);

        verify(userChallengeRepository).save(argThat(userChallenge ->
                userChallenge.getUserId().equals(7L)
                        && userChallenge.getChallengeId().equals(99L)
                        && Boolean.TRUE.equals(userChallenge.getActive())
                        && Boolean.FALSE.equals(userChallenge.getCompleted())
                        && userChallenge.getJoinedAt() != null
        ));
    }

    @Test
    void createChallengePersistsOneOffModeWithSingleDayDuration() throws Exception {
        ChallengeCreateRequest request = new ChallengeCreateRequest();
        request.setTitle("Clean Closet");
        request.setDescription("Finish one closet cleanup whenever you can");
        request.setCreatorUserId(7L);
        request.setStartDate(LocalDate.of(2026, 5, 16));
        request.setNumberOfDays(30);
        request.setChallengeMode("ONE_OFF");

        when(userRepository.existsById(7L)).thenReturn(true);
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> {
            Challenge challenge = invocation.getArgument(0);
            challenge.setId(99L);
            return challenge;
        });

        ChallengeCreateResponse response = challengeService.createChallenge(request);

        assertEquals("ONE_OFF", response.getChallengeMode());
        assertEquals(1, response.getNumberOfDays());
        verify(challengeRepository).save(argThat(challenge ->
                "ONE_OFF".equals(challenge.getChallengeMode())
                        && Integer.valueOf(1).equals(challenge.getNumberOfDays())
        ));
    }

    @Test
    void updateChallengeAcceptsSingleMediaUrl() throws Exception {
        Challenge existing = challenge(11L, 7L, 7L, true);
        existing.setCoverPhotoUrls("https://old.example.com/cover.jpg");

        ChallengeUpdateRequest request = new ChallengeUpdateRequest();
        request.setMediaUrl("challenges/user_7/covers/new.jpg");

        when(challengeRepository.findById(11L)).thenReturn(Optional.of(existing));
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mediaUrlService.toReadableMediaUrls(
                "https://storage.googleapis.com/challenge-bucket/challenges/user_7/covers/new.jpg"
        )).thenReturn("https://signed.example.com/new.jpg");

        Challenge updated = challengeService.updateChallenge(11L, request, 7L, false);

        assertEquals(
                "https://signed.example.com/new.jpg",
                updated.getCoverPhotoUrls()
        );
        assertEquals(
                "https://storage.googleapis.com/challenge-bucket/challenges/user_7/covers/new.jpg",
                existing.getCoverPhotoUrls()
        );
    }

    @Test
    void deleteChallengesSoftDeletesChallenge() {
        Challenge challenge = challenge(10L, 1L, 1L, true);

        when(challengeRepository.findAllById(List.of(10L))).thenReturn(List.of(challenge));
        when(userChallengeRepository.findByChallengeIds(List.of(10L))).thenReturn(List.of());

        challengeService.deleteChallenges(List.of(10L));

        assertFalse(challenge.getActive());
        verify(challengeRepository).saveAll(List.of(challenge));
        verify(cacheService).invalidateChallenge(10L);
        verify(cacheService).invalidateAllFeeds();
    }

    @Test
    void deleteChallengesMarksParticipantLinksInactive() {
        Challenge challenge = challenge(10L, 1L, 1L, true);
        UserChallenge participant = new UserChallenge();
        participant.setId(20L);
        participant.setChallengeId(10L);
        participant.setUserId(2L);
        participant.setActive(true);

        when(challengeRepository.findAllById(List.of(10L))).thenReturn(List.of(challenge));
        when(userChallengeRepository.findByChallengeIds(List.of(10L))).thenReturn(List.of(participant));

        challengeService.deleteChallenges(List.of(10L));

        assertEquals(false, participant.getActive());
        verify(userChallengeRepository).saveAll(List.of(participant));
    }

    @Test
    void getUserChallengesIncludesDeletedChallengesWhenUnfiltered() throws Exception {
        Challenge active = challenge(10L, 1L, 1L, true);
        Challenge deleted = challenge(11L, 1L, 1L, false);
        when(challengeRepository.findByCreatorId(1L)).thenReturn(List.of(active, deleted));
        when(challengeRepository.findByCurrentOwnerId(1L)).thenReturn(List.of());
        when(userChallengeRepository.findActiveByUserId(1L)).thenReturn(List.of());

        GetUserChallengesRequest request = new GetUserChallengesRequest();
        request.setUserId(1L);
        request.setCurrentUserId(1L);
        request.setPage(1);
        request.setPageSize(10);

        UserChallengesResponse response = challengeService.getUserChallenges(request);

        assertEquals(2, response.getTotalCount());
        assertEquals(List.of(active, deleted), response.getChallenges());
        assertEquals(false, response.getChallenges().get(1).getActive());
    }

    @Test
    void getUserChallengesReturnsReadableCoverPhotoUrlsWithoutMutatingEntity() throws Exception {
        String rawCoverUrl = "https://storage.googleapis.com/challenge-bucket/challenges/user_1/covers/run.jpg";
        Challenge active = challenge(10L, 1L, 1L, true);
        active.setCoverPhotoUrls(rawCoverUrl);

        when(challengeRepository.findByCreatorId(1L)).thenReturn(List.of(active));
        when(challengeRepository.findByCurrentOwnerId(1L)).thenReturn(List.of());
        when(userChallengeRepository.findActiveByUserId(1L)).thenReturn(List.of());
        when(mediaUrlService.toReadableMediaUrls(rawCoverUrl)).thenReturn("https://signed.example.com/run.jpg");

        GetUserChallengesRequest request = new GetUserChallengesRequest();
        request.setUserId(1L);
        request.setCurrentUserId(1L);
        request.setPage(1);
        request.setPageSize(10);

        UserChallengesResponse response = challengeService.getUserChallenges(request);

        assertEquals("https://signed.example.com/run.jpg", response.getChallenges().get(0).getCoverPhotoUrls());
        assertEquals(rawCoverUrl, active.getCoverPhotoUrls());
    }

    @Test
    void getChallengeByIdsReturnsReadableCoverPhotoUrlsWithoutMutatingEntity() throws Exception {
        String rawCoverUrl = "https://storage.googleapis.com/challenge-bucket/challenges/user_1/covers/detail.jpg";
        Challenge challenge = challenge(10L, 1L, 1L, true);
        challenge.setCoverPhotoUrls(rawCoverUrl);

        when(challengeRepository.findAllById(List.of(10L))).thenReturn(List.of(challenge));
        when(mediaUrlService.toReadableMediaUrls(rawCoverUrl)).thenReturn("https://signed.example.com/detail.jpg");

        List<Challenge> response = challengeService.getChallengeByIds(List.of(10L), 1L);

        assertEquals("https://signed.example.com/detail.jpg", response.get(0).getCoverPhotoUrls());
        assertEquals(rawCoverUrl, challenge.getCoverPhotoUrls());
    }

    @Test
    void getMyChallengesCountsDistinctSubmittedDaysForCompletionPercentage() throws Exception {
        Challenge challenge = challenge(10L, 1L, 1L, true);
        UserChallenge userChallenge = new UserChallenge();
        userChallenge.setUserId(2L);
        userChallenge.setChallengeId(10L);
        userChallenge.setActive(true);
        userChallenge.setCompleted(false);

        when(userChallengeRepository.findActiveByUserId(2L)).thenReturn(List.of(userChallenge));
        when(challengeRepository.findAllById(List.of(10L))).thenReturn(List.of(challenge));
        when(submissionRepository.findActiveByUserIdAndChallengeId(2L, 10L)).thenReturn(List.of(
                submission(100L, 2L, 10L, 1),
                submission(101L, 2L, 10L, 1),
                submission(102L, 2L, 10L, 2)
        ));

        MyChallengesResponse response = challengeService.getMyChallenges(2L, 2L, null, 1, 10);

        assertEquals(2, response.getChallenges().get(0).getDaysCompleted());
        assertEquals(29, response.getChallenges().get(0).getCompletionPercentage());
    }

    @Test
    void getMyChallengesMarksOneOffCompleteFromActiveSubmission() throws Exception {
        Challenge challenge = challenge(10L, 1L, 1L, true);
        challenge.setChallengeMode("ONE_OFF");
        challenge.setNumberOfDays(1);
        UserChallenge userChallenge = new UserChallenge();
        userChallenge.setUserId(2L);
        userChallenge.setChallengeId(10L);
        userChallenge.setActive(true);
        userChallenge.setCompleted(false);

        when(userChallengeRepository.findActiveByUserId(2L)).thenReturn(List.of(userChallenge));
        when(challengeRepository.findAllById(List.of(10L))).thenReturn(List.of(challenge));
        when(submissionRepository.findActiveByUserIdAndChallengeId(2L, 10L)).thenReturn(List.of(
                submission(100L, 2L, 10L, 1)
        ));

        MyChallengesResponse response = challengeService.getMyChallenges(2L, 2L, null, 1, 10);

        assertEquals("ONE_OFF", response.getChallenges().get(0).getChallengeMode());
        assertEquals(1, response.getChallenges().get(0).getDaysCompleted());
        assertEquals(100, response.getChallenges().get(0).getCompletionPercentage());
        assertEquals(true, response.getChallenges().get(0).isCompleted());
    }

    @Test
    void joinChallengeReactivatesInactiveParticipantLink() throws Exception {
        Challenge challenge = challenge(10L, 1L, 1L, true);
        User user = new User();
        user.setId(2L);
        user.setChallengesAccepted(0);
        UserChallenge inactiveLink = new UserChallenge();
        inactiveLink.setId(20L);
        inactiveLink.setChallengeId(10L);
        inactiveLink.setUserId(2L);
        inactiveLink.setActive(false);
        inactiveLink.setCompleted(true);

        when(challengeRepository.findById(10L)).thenReturn(Optional.of(challenge));
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(user));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(userChallengeRepository.findByChallengeId(10L)).thenReturn(List.of(inactiveLink));

        JoinChallengeRequest request = new JoinChallengeRequest();
        request.setChallengeId(10L);
        request.setUserIds(List.of(2L));

        JoinChallengeResponse response = challengeService.joinChallenge(request);

        assertEquals(List.of(2L), response.getSuccessfulUserIds());
        assertEquals(true, inactiveLink.getActive());
        assertEquals(false, inactiveLink.getCompleted());
        verify(userChallengeRepository).saveAll(List.of(inactiveLink));
    }

    @Test
    void joinChallengeNotifiesChallengeOwnerWhenParticipantJoins() throws Exception {
        Challenge challenge = challenge(10L, 1L, 1L, true);
        User user = new User();
        user.setId(2L);
        user.setChallengesAccepted(0);

        when(challengeRepository.findById(10L)).thenReturn(Optional.of(challenge));
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(user));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(userChallengeRepository.findByChallengeId(10L)).thenReturn(List.of());

        JoinChallengeRequest request = new JoinChallengeRequest();
        request.setChallengeId(10L);
        request.setUserIds(List.of(2L));

        JoinChallengeResponse response = challengeService.joinChallenge(request);

        assertEquals(List.of(2L), response.getSuccessfulUserIds());
        verify(notificationService).notifyChallengeJoined(
                argThat(savedChallenge -> savedChallenge.getId().equals(10L)),
                argThat(joiningUserId -> joiningUserId.equals(2L)),
                argThat(recipientIds -> recipientIds.size() == 1 && recipientIds.contains(1L))
        );
    }

    private static Challenge challenge(Long id, Long creatorId, Long ownerId, boolean active) {
        Challenge challenge = new Challenge();
        challenge.setId(id);
        challenge.setCreatorId(creatorId);
        challenge.setCurrentOwnerId(ownerId);
        challenge.setActive(active);
        challenge.setIsPrivate(false);
        challenge.setTitle("Challenge " + id);
        challenge.setDescription("Description");
        challenge.setNumberOfDays(7);
        return challenge;
    }

    private static Submission submission(Long id, Long userId, Long challengeId, int dayNumber) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setUserId(userId);
        submission.setChallengeId(challengeId);
        submission.setDayNumber(dayNumber);
        submission.setDescription("Proof");
        submission.setSubmissionTime(new Timestamp(System.currentTimeMillis()));
        submission.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        submission.setActive(true);
        return submission;
    }
}
