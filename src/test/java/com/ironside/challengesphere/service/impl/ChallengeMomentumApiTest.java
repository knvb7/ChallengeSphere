package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.ChallengeProgressResponse;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.api.response.MyChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeMomentumApiTest {

    private static final long USER_ID = 7L;
    private static final long CHALLENGE_ID = 41L;

    private ChallengeRepository challengeRepository;
    private UserChallengeRepository userChallengeRepository;
    private SubmissionRepository submissionRepository;
    private ChallengeCrewLifecycleRepository lifecycleRepository;
    private FollowRepository followRepository;
    private LeaderboardService leaderboardService;
    private ChallengeServiceImpl service;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        lifecycleRepository = mock(ChallengeCrewLifecycleRepository.class);
        followRepository = mock(FollowRepository.class);
        leaderboardService = mock(LeaderboardService.class);

        service = new ChallengeServiceImpl();
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "challengeCrewLifecycleRepository", lifecycleRepository);
        ReflectionTestUtils.setField(service, "followRepository", followRepository);
        ReflectionTestUtils.setField(service, "leaderboardService", leaderboardService);
        ReflectionTestUtils.setField(service, "challengeMomentumPolicy", new ChallengeMomentumPolicy());
        ReflectionTestUtils.setField(service, "userRepository", mock(UserRepository.class));
    }

    @Test
    void myProgressIncludesCanonicalMomentumSnapshot() throws Exception {
        Challenge challenge = dailyChallenge(false);
        UserChallenge membership = activeMembership();
        List<Submission> submissions = submissionsThroughDay(21);

        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership));
        when(submissionRepository.findActiveByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(submissions);

        ChallengeProgressResponse response = service.getChallengeProgress(CHALLENGE_ID, USER_ID);

        assertNotNull(response.getMomentum());
        assertEquals(ChallengeMomentumPolicy.State.FINAL_STRETCH, response.getMomentum().getState());
        assertEquals(30, response.getMomentum().getTotalDays());
        assertEquals(21, response.getMomentum().getCompletedDays());
        assertEquals(9, response.getMomentum().getRemainingRequiredCheckIns());
        assertEquals(9, response.getMomentum().getRemainingAvailableCheckIns());
    }

    @Test
    void myProgressUsesTheSuppliedUserLocalDateForSubmittedToday() throws Exception {
        Challenge challenge = dailyChallenge(false);
        challenge.setStartDate(LocalDate.parse("2026-07-01"));
        UserChallenge membership = activeMembership();

        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(membership));
        when(submissionRepository.findActiveByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(submissionsThroughDay(22));

        ChallengeProgressResponse response = service.getChallengeProgress(
                CHALLENGE_ID,
                USER_ID,
                LocalDate.parse("2026-07-22")
        );

        assertNotNull(response.getMomentum());
        assertEquals(ChallengeMomentumPolicy.State.FINAL_STRETCH, response.getMomentum().getState());
        assertEquals(8, response.getMomentum().getRemainingRequiredCheckIns());
        assertEquals(8, response.getMomentum().getRemainingAvailableCheckIns());
        assertTrue(response.getMomentum().isSubmittedToday());
    }

    @Test
    void myChallengesUsesUserLocalDateAtTheChallengeEndBoundary() throws Exception {
        Challenge challenge = dailyChallenge(false);
        challenge.setStartDate(LocalDate.parse("2026-07-01"));
        challenge.setNumberOfDays(7);
        UserChallenge membership = activeMembership();
        when(userChallengeRepository.findActiveByUserId(USER_ID)).thenReturn(List.of(membership));
        when(userChallengeRepository.findActiveByChallengeIds(List.of(CHALLENGE_ID)))
                .thenReturn(List.of(membership));
        when(challengeRepository.findAllById(List.of(CHALLENGE_ID))).thenReturn(List.of(challenge));
        when(submissionRepository.findActiveByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(List.of());

        MyChallengesResponse finalDay = service.getMyChallenges(
                USER_ID, USER_ID, null, 1, 10, LocalDate.parse("2026-07-07"));
        MyChallengesResponse nextDay = service.getMyChallenges(
                USER_ID, USER_ID, null, 1, 10, LocalDate.parse("2026-07-08"));

        assertFalse(finalDay.getChallenges().get(0).isCompleted());
        assertNotNull(finalDay.getChallenges().get(0).getMomentum());
        assertEquals(1, finalDay.getChallenges().get(0).getMomentum().getRemainingAvailableCheckIns());
        assertTrue(nextDay.getChallenges().get(0).isCompleted());
        assertNull(nextDay.getChallenges().get(0).getMomentum());
    }

    @Test
    void anotherUsersMyChallengesViewDoesNotExposeSelfMotivationOrRank() throws Exception {
        Challenge challenge = dailyChallenge(false);
        UserChallenge membership = activeMembership();
        stubMyChallenges(challenge, membership, startedLifecycle());
        when(followRepository.existsActiveMutualFollow(99L, USER_ID)).thenReturn(true);

        MyChallengesResponse.ChallengeSummary summary = service.getMyChallenges(
                USER_ID, 99L, null, 1, 10, LocalDate.now()).getChallenges().get(0);

        assertNull(summary.getMomentum());
        assertNull(summary.getPrivateRank());
    }

    @Test
    void myChallengesIncludesPrivateRankFromExistingRankService() throws Exception {
        Challenge challenge = dailyChallenge(true);
        UserChallenge membership = activeMembership();
        ChallengeCrewLifecycle lifecycle = startedLifecycle();
        LeaderboardResponse.UserRankDTO rank = LeaderboardResponse.UserRankDTO.builder()
                .userId(USER_ID)
                .rank(4L)
                .totalParticipants(6L)
                .score(100.0)
                .percentile(40.0)
                .build();
        stubMyChallenges(challenge, membership, lifecycle);
        when(leaderboardService.getUserRank(CHALLENGE_ID, USER_ID, USER_ID)).thenReturn(rank);

        MyChallengesResponse response = service.getMyChallenges(USER_ID, USER_ID, null, 1, 10);

        MyChallengesResponse.ChallengeSummary summary = response.getChallenges().get(0);
        assertNotNull(summary.getMomentum());
        assertNotNull(summary.getPrivateRank());
        assertEquals(4L, summary.getPrivateRank().getRank());
        assertEquals(6L, summary.getPrivateRank().getTotalParticipants());
    }

    @Test
    void privateLobbyOmitsMomentumAndRank() throws Exception {
        Challenge challenge = dailyChallenge(true);
        UserChallenge membership = activeMembership();
        ChallengeCrewLifecycle lifecycle = new ChallengeCrewLifecycle();
        lifecycle.setChallengeId(CHALLENGE_ID);
        lifecycle.setStartedAt(null);
        stubMyChallenges(challenge, membership, lifecycle);

        MyChallengesResponse.ChallengeSummary summary = service
                .getMyChallenges(USER_ID, USER_ID, null, 1, 10)
                .getChallenges().get(0);

        assertNull(summary.getMomentum());
        assertNull(summary.getPrivateRank());
        verify(leaderboardService, never()).getUserRank(CHALLENGE_ID, USER_ID, USER_ID);
    }

    @Test
    void missingPrivateLifecycleIsTreatedAsLobby() throws Exception {
        Challenge challenge = dailyChallenge(true);
        UserChallenge membership = activeMembership();
        when(userChallengeRepository.findActiveByUserId(USER_ID)).thenReturn(List.of(membership));
        when(userChallengeRepository.findActiveByChallengeIds(List.of(CHALLENGE_ID)))
                .thenReturn(List.of(membership));
        when(challengeRepository.findAllById(List.of(CHALLENGE_ID))).thenReturn(List.of(challenge));
        when(lifecycleRepository.findAllById(List.of(CHALLENGE_ID))).thenReturn(List.of());
        when(submissionRepository.findActiveByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(submissionsThroughDay(21));

        MyChallengesResponse.ChallengeSummary summary = service
                .getMyChallenges(USER_ID, USER_ID, null, 1, 10)
                .getChallenges().get(0);

        assertNull(summary.getMomentum());
        assertNull(summary.getPrivateRank());
        verify(leaderboardService, never()).getUserRank(CHALLENGE_ID, USER_ID, USER_ID);
    }

    @Test
    void stalePrivateRankDoesNotFailMyChallengesResponse() throws Exception {
        Challenge challenge = dailyChallenge(true);
        UserChallenge membership = activeMembership();
        stubMyChallenges(challenge, membership, startedLifecycle());
        when(leaderboardService.getUserRank(CHALLENGE_ID, USER_ID, USER_ID))
                .thenThrow(new ChallengeSphereException("stale leaderboard", HttpStatus.NOT_FOUND, null));

        MyChallengesResponse.ChallengeSummary summary = service
                .getMyChallenges(USER_ID, USER_ID, null, 1, 10)
                .getChallenges().get(0);

        assertNotNull(summary.getMomentum());
        assertNull(summary.getPrivateRank());
    }

    private void stubMyChallenges(
            Challenge challenge,
            UserChallenge membership,
            ChallengeCrewLifecycle lifecycle
    ) {
        when(userChallengeRepository.findActiveByUserId(USER_ID)).thenReturn(List.of(membership));
        when(userChallengeRepository.findActiveByChallengeIds(List.of(CHALLENGE_ID)))
                .thenReturn(List.of(membership));
        when(challengeRepository.findAllById(List.of(CHALLENGE_ID))).thenReturn(List.of(challenge));
        when(lifecycleRepository.findAllById(List.of(CHALLENGE_ID))).thenReturn(List.of(lifecycle));
        when(submissionRepository.findActiveByUserIdAndChallengeId(USER_ID, CHALLENGE_ID))
                .thenReturn(submissionsThroughDay(21));
    }

    private Challenge dailyChallenge(boolean privateChallenge) {
        Challenge challenge = new Challenge();
        challenge.setId(CHALLENGE_ID);
        challenge.setTitle("Thirty-day challenge");
        challenge.setDescription("Show up daily");
        challenge.setCreatorId(USER_ID);
        challenge.setCurrentOwnerId(USER_ID);
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        challenge.setNumberOfDays(30);
        challenge.setStartDate(LocalDate.now().minusDays(21));
        challenge.setActive(true);
        challenge.setIsPrivate(privateChallenge);
        challenge.setCreatedAt(Timestamp.from(Instant.now()));
        return challenge;
    }

    private UserChallenge activeMembership() {
        UserChallenge membership = new UserChallenge();
        membership.setUserId(USER_ID);
        membership.setChallengeId(CHALLENGE_ID);
        membership.setActive(true);
        membership.setBanned(false);
        membership.setCompleted(false);
        return membership;
    }

    private ChallengeCrewLifecycle startedLifecycle() {
        ChallengeCrewLifecycle lifecycle = new ChallengeCrewLifecycle();
        lifecycle.setChallengeId(CHALLENGE_ID);
        lifecycle.setStartedAt(Timestamp.from(Instant.now().minusSeconds(3600)));
        return lifecycle;
    }

    private List<Submission> submissionsThroughDay(int finalDay) {
        return IntStream.rangeClosed(1, finalDay)
                .mapToObj(this::submission)
                .toList();
    }

    private Submission submission(int dayNumber) {
        Submission submission = new Submission();
        submission.setId((long) dayNumber);
        submission.setUserId(USER_ID);
        submission.setChallengeId(CHALLENGE_ID);
        submission.setDayNumber(dayNumber);
        submission.setGloriesCount(0L);
        submission.setActive(true);
        return submission;
    }
}
