package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewRecapResponse;
import com.ironside.challengesphere.api.response.ChallengeCrewStatusResponse;
import com.ironside.challengesphere.api.response.ChallengeRematchResponse;
import com.ironside.challengesphere.api.response.PeerNudgeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.ChallengePeerReminderPreference;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeCrewInviteRepository;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.ChallengePeerNudgeRepository;
import com.ironside.challengesphere.repository.ChallengePeerReminderPreferenceRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeCrewServiceImplTest {
    private ChallengeRepository challengeRepository;
    private UserChallengeRepository userChallengeRepository;
    private SubmissionRepository submissionRepository;
    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private ChallengeCrewLifecycleRepository lifecycleRepository;
    private ChallengeCrewInviteRepository inviteRepository;
    private ChallengePeerReminderPreferenceRepository reminderPreferenceRepository;
    private ChallengePeerNudgeRepository nudgeRepository;
    private ChallengeService challengeService;
    private NotificationService notificationService;
    private ChallengeCrewServiceImpl service;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        lifecycleRepository = mock(ChallengeCrewLifecycleRepository.class);
        inviteRepository = mock(ChallengeCrewInviteRepository.class);
        reminderPreferenceRepository = mock(ChallengePeerReminderPreferenceRepository.class);
        nudgeRepository = mock(ChallengePeerNudgeRepository.class);
        challengeService = mock(ChallengeService.class);
        notificationService = mock(NotificationService.class);
        service = new ChallengeCrewServiceImpl(
                challengeRepository,
                userChallengeRepository,
                submissionRepository,
                userRepository,
                userBlockRepository,
                lifecycleRepository,
                inviteRepository,
                reminderPreferenceRepository,
                nudgeRepository,
                challengeService,
                notificationService,
                new ObjectMapper()
        );
    }

    @Test
    void ownerStartsLobbySolo() throws Exception {
        Challenge challenge = privateChallenge(41L, 7L);
        ChallengeCrewLifecycle lifecycle = lobbyLifecycle(41L);
        when(challengeRepository.findById(41L)).thenReturn(Optional.of(challenge));
        when(lifecycleRepository.findByChallengeIdForUpdate(41L)).thenReturn(Optional.of(lifecycle));
        when(userChallengeRepository.findActiveByChallengeId(41L))
                .thenReturn(List.of(membership(7L, 41L, false)));

        ChallengeCrewStatusResponse status = service.startTogether(41L, 7L);

        assertEquals("ACTIVE", status.getState());
        assertEquals(1, status.getAcceptedCount());
        assertEquals(1, status.getMinimumStartCount());
        assertNotNull(status.getStartedAt());
        assertFalse(status.isCanStart());
        assertEquals(LocalDate.now(), challenge.getStartDate());
        verify(lifecycleRepository).save(lifecycle);
        verify(challengeRepository).save(challenge);
    }

    @Test
    void checkInsStayClosedWhilePrivateChallengeIsInLobby() {
        Challenge challenge = privateChallenge(42L, 7L);
        when(challengeRepository.findById(42L)).thenReturn(Optional.of(challenge));
        when(lifecycleRepository.findById(42L)).thenReturn(Optional.of(lobbyLifecycle(42L)));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.requireStarted(42L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(
                "This private challenge is still in the lobby. The creator needs to start it before check-ins open.",
                error.getResponseMessage()
        );
    }

    @Test
    void consentedPeerCheckInIsNeutralAndStartsRecipientCooldown() throws Exception {
        Challenge challenge = privateChallenge(43L, 7L);
        ChallengeCrewLifecycle lifecycle = startedLifecycle(43L, Instant.now().minus(2, ChronoUnit.HOURS));
        UserChallenge senderMembership = membership(7L, 43L, false);
        UserChallenge recipientMembership = membership(8L, 43L, false);
        ChallengePeerReminderPreference preference = reminderPreference(43L, 8L, true);
        User sender = new User();
        sender.setId(7L);
        sender.setUsername("alex");

        when(challengeRepository.findById(43L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 43L))
                .thenReturn(Optional.of(senderMembership));
        when(userChallengeRepository.findByUserIdAndChallengeId(8L, 43L))
                .thenReturn(Optional.of(recipientMembership));
        when(userChallengeRepository.findActiveByChallengeId(43L))
                .thenReturn(List.of(senderMembership, recipientMembership));
        when(lifecycleRepository.findById(43L)).thenReturn(Optional.of(lifecycle));
        when(reminderPreferenceRepository.findByChallengeIdAndUserIdForUpdate(43L, 8L))
                .thenReturn(Optional.of(preference));
        when(userRepository.findById(7L)).thenReturn(Optional.of(sender));

        PeerNudgeResponse response = service.sendPeerNudge(43L, 7L, 8L);

        assertEquals("Quiet check-in sent", response.getMessage());
        assertNotNull(preference.getLastNudgedAt());
        assertEquals(24, ChronoUnit.HOURS.between(
                response.getSentAt().toInstant(),
                response.getNextAllowedAt().toInstant()
        ));
        verify(notificationService).notifyChallengeUpdate(
                eq(8L),
                eq("@alex sent a quiet check-in for Morning walk. Join in when it works for you."),
                any(String.class)
        );
        verify(nudgeRepository).save(any());
    }

    @Test
    void peerCheckInHonorsRecipientCooldown() {
        Challenge challenge = privateChallenge(44L, 7L);
        ChallengeCrewLifecycle lifecycle = startedLifecycle(44L, Instant.now().minus(2, ChronoUnit.HOURS));
        UserChallenge senderMembership = membership(7L, 44L, false);
        UserChallenge recipientMembership = membership(8L, 44L, false);
        ChallengePeerReminderPreference preference = reminderPreference(44L, 8L, true);
        preference.setLastNudgedAt(Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        when(challengeRepository.findById(44L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 44L))
                .thenReturn(Optional.of(senderMembership));
        when(userChallengeRepository.findByUserIdAndChallengeId(8L, 44L))
                .thenReturn(Optional.of(recipientMembership));
        when(userChallengeRepository.findActiveByChallengeId(44L))
                .thenReturn(List.of(senderMembership, recipientMembership));
        when(lifecycleRepository.findById(44L)).thenReturn(Optional.of(lifecycle));
        when(reminderPreferenceRepository.findByChallengeIdAndUserIdForUpdate(44L, 8L))
                .thenReturn(Optional.of(preference));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.sendPeerNudge(44L, 7L, 8L)
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        verify(notificationService, never()).notifyChallengeUpdate(any(), any(), any());
    }

    @Test
    void completedCrewGetsAggregateRecapWithoutRankingPeople() throws Exception {
        Challenge challenge = privateChallenge(45L, 7L);
        challenge.setStartDate(LocalDate.now().minusDays(3));
        challenge.setNumberOfDays(2);
        ChallengeCrewLifecycle lifecycle = startedLifecycle(45L, Instant.now().minus(3, ChronoUnit.DAYS));
        List<UserChallenge> participants = List.of(
                membership(7L, 45L, false),
                membership(8L, 45L, false)
        );
        when(challengeRepository.findById(45L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findActiveByChallengeId(45L)).thenReturn(participants);
        when(lifecycleRepository.findById(45L)).thenReturn(Optional.of(lifecycle));
        when(submissionRepository.findActiveByChallengeId(45L)).thenReturn(List.of(
                submission(7L, 45L, 1),
                submission(7L, 45L, 1),
                submission(8L, 45L, 1)
        ));

        ChallengeCrewRecapResponse recap = service.getRecap(45L, 7L);

        assertEquals(2, recap.getParticipantCount());
        assertEquals(2, recap.getContributorCount());
        assertEquals(2, recap.getCheckInCount());
        assertEquals(4, recap.getTotalPossibleCheckIns());
        assertEquals(50, recap.getCollectiveCompletionPercent());
        assertNotNull(recap.getRecapReadyAt());
    }

    @Test
    void rematchClonesSettingsAndInvitesCrewWithoutAutoEnrollingThem() throws Exception {
        Challenge challenge = privateChallenge(46L, 7L);
        challenge.setChallengeMode(Challenge.MODE_ONE_OFF);
        challenge.setProofMode(Challenge.PROOF_MODE_PHOTO_REQUIRED);
        challenge.setDescription("Take a calm walk together.\n\nRules & Guidelines:\nComplete one walk this week."
                + "\n\nBenefits:\nBuild consistency together.");
        ChallengeCrewLifecycle lifecycle = startedLifecycle(46L, Instant.now().minus(2, ChronoUnit.DAYS));
        UserChallenge owner = membership(7L, 46L, true);
        UserChallenge friend = membership(8L, 46L, true);
        ChallengeCreateResponse createResponse = new ChallengeCreateResponse();
        createResponse.setChallengeId(146L);

        when(challengeRepository.findById(46L)).thenReturn(Optional.of(challenge));
        when(lifecycleRepository.findByChallengeIdForUpdate(46L)).thenReturn(Optional.of(lifecycle));
        when(userChallengeRepository.findActiveByChallengeId(46L)).thenReturn(List.of(owner, friend));
        when(lifecycleRepository.findByRematchOfChallengeId(46L)).thenReturn(Optional.empty());
        when(challengeService.createChallenge(any())).thenReturn(createResponse);
        when(inviteRepository.findByChallengeIdAndInvitedUserId(146L, 8L)).thenReturn(Optional.empty());
        when(lifecycleRepository.findByChallengeIdForUpdate(146L)).thenReturn(Optional.empty());

        ChallengeRematchResponse response = service.rematch(46L, 7L);

        assertEquals(146L, response.getChallengeId());
        assertEquals(1, response.getCopiedInviteCount());
        ArgumentCaptor<ChallengeCreateRequest> requestCaptor = ArgumentCaptor.forClass(ChallengeCreateRequest.class);
        verify(challengeService).createChallenge(requestCaptor.capture());
        ChallengeCreateRequest request = requestCaptor.getValue();
        assertTrue(request.getIsPrivate());
        assertEquals(Challenge.PROOF_MODE_PHOTO_REQUIRED, request.getProofMode());
        assertEquals(List.of(8L), request.getInvitedUserIds());
        verify(inviteRepository).save(any());
        verify(userChallengeRepository, never()).save(any());

        ArgumentCaptor<ChallengeCrewLifecycle> lifecycleCaptor =
                ArgumentCaptor.forClass(ChallengeCrewLifecycle.class);
        verify(lifecycleRepository, org.mockito.Mockito.atLeastOnce()).save(lifecycleCaptor.capture());
        ChallengeCrewLifecycle savedRematchLifecycle = lifecycleCaptor.getAllValues().stream()
                .filter(value -> Long.valueOf(146L).equals(value.getChallengeId()))
                .findFirst()
                .orElseThrow();
        assertEquals(46L, savedRematchLifecycle.getRematchOfChallengeId());
        assertNull(savedRematchLifecycle.getStartedAt());
    }

    private Challenge privateChallenge(Long challengeId, Long ownerUserId) {
        Challenge challenge = new Challenge();
        challenge.setId(challengeId);
        challenge.setTitle("Morning walk");
        challenge.setDescription("Take a calm walk together.\n\nRules & Guidelines:\nComplete one walk each day.");
        challenge.setCategories("Health");
        challenge.setHashtags("wellness");
        challenge.setBenefits("Build consistency together.");
        challenge.setIsPrivate(true);
        challenge.setActive(true);
        challenge.setCreatorId(ownerUserId);
        challenge.setCurrentOwnerId(ownerUserId);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(7);
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        challenge.setProofMode(Challenge.PROOF_MODE_FLEXIBLE);
        challenge.setCreatedAt(Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        return challenge;
    }

    private ChallengeCrewLifecycle lobbyLifecycle(Long challengeId) {
        ChallengeCrewLifecycle lifecycle = new ChallengeCrewLifecycle();
        lifecycle.setChallengeId(challengeId);
        lifecycle.setCreatedAt(Timestamp.from(Instant.now()));
        lifecycle.setUpdatedAt(Timestamp.from(Instant.now()));
        return lifecycle;
    }

    private ChallengeCrewLifecycle startedLifecycle(Long challengeId, Instant startedAt) {
        ChallengeCrewLifecycle lifecycle = lobbyLifecycle(challengeId);
        lifecycle.setStartedAt(Timestamp.from(startedAt));
        lifecycle.setStartedByUserId(7L);
        return lifecycle;
    }

    private UserChallenge membership(Long userId, Long challengeId, boolean completed) {
        UserChallenge membership = new UserChallenge();
        membership.setUserId(userId);
        membership.setChallengeId(challengeId);
        membership.setActive(true);
        membership.setBanned(false);
        membership.setCompleted(completed);
        return membership;
    }

    private ChallengePeerReminderPreference reminderPreference(
            Long challengeId,
            Long userId,
            boolean enabled
    ) {
        ChallengePeerReminderPreference preference = new ChallengePeerReminderPreference();
        preference.setChallengeId(challengeId);
        preference.setUserId(userId);
        preference.setEnabled(enabled);
        preference.setCreatedAt(Timestamp.from(Instant.now()));
        preference.setUpdatedAt(Timestamp.from(Instant.now()));
        return preference;
    }

    private Submission submission(Long userId, Long challengeId, int dayNumber) {
        Submission submission = new Submission();
        submission.setUserId(userId);
        submission.setChallengeId(challengeId);
        submission.setDayNumber(dayNumber);
        submission.setActive(true);
        return submission;
    }
}
