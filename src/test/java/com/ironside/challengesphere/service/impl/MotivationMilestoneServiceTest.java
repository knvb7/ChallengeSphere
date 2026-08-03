package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.MotivationReminderRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeMilestoneRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.PushNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MotivationMilestoneServiceTest {

    private ChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private UserChallengeRepository userChallengeRepository;
    private ChallengeCrewLifecycleRepository lifecycleRepository;
    private SubmissionRepository submissionRepository;
    private MotivationReminderRepository reminderRepository;
    private UserPreferencesRepository preferencesRepository;
    private UserChallengeMilestoneRepository milestoneRepository;
    private NotificationRepository notificationRepository;
    private LeaderboardService leaderboardService;
    private PushNotificationService pushNotificationService;
    private MotivationMilestoneService service;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        lifecycleRepository = mock(ChallengeCrewLifecycleRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        reminderRepository = mock(MotivationReminderRepository.class);
        preferencesRepository = mock(UserPreferencesRepository.class);
        milestoneRepository = mock(UserChallengeMilestoneRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        leaderboardService = mock(LeaderboardService.class);
        pushNotificationService = mock(PushNotificationService.class);
        service = new MotivationMilestoneService(
                challengeRepository,
                userRepository,
                userChallengeRepository,
                lifecycleRepository,
                submissionRepository,
                reminderRepository,
                preferencesRepository,
                milestoneRepository,
                notificationRepository,
                leaderboardService,
                pushNotificationService,
                new ChallengeMomentumPolicy(),
                Clock.fixed(Instant.parse("2026-07-22T19:30:00Z"), ZoneOffset.UTC)
        );
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(91L);
            return notification;
        });
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void finalStretchClaimAndInboxAreDurableBeforePushDispatch() {
        Challenge challenge = challenge(11L, false, LocalDate.parse("2026-07-01"), 30);
        stubEligibleParticipant(challenge, false);
        when(submissionRepository.findActiveByUserIdAndChallengeId(7L, 11L))
                .thenReturn(submissions(11L, 22));
        when(milestoneRepository.claim(7L, 11L, MotivationMilestoneService.FINAL_STRETCH))
                .thenReturn(1);

        service.evaluateAfterSubmission(7L, 11L, LocalDate.parse("2026-07-22"), false);

        verify(milestoneRepository).claim(7L, 11L, MotivationMilestoneService.FINAL_STRETCH);
        verify(notificationRepository).saveAndFlush(any(Notification.class));
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());

        Notification saved = notificationFromSave();
        assertEquals("MOMENTUM_MILESTONE", saved.getType());
        assertTrue(saved.getMessage().startsWith("Final stretch:"));
        assertTrue(saved.getActionObject().contains("\"notificationId\":91"));
        assertTrue(saved.getActionObject().contains("\"remainingRequiredCheckIns\":8"));
        assertFalse(saved.getActionObject().contains("actor"));
        assertFalse(saved.getActionObject().contains("username"));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(pushNotificationService).sendInteractionPush(
                eq(7L),
                eq("MOMENTUM_MILESTONE"),
                eq(saved.getMessage()),
                eq(saved.getActionObject())
        );
    }

    @Test
    void duplicateFinalStretchClaimDoesNotCreateAnotherInboxRow() {
        Challenge challenge = challenge(11L, false, LocalDate.parse("2026-07-01"), 30);
        stubEligibleParticipant(challenge, false);
        when(submissionRepository.findActiveByUserIdAndChallengeId(7L, 11L))
                .thenReturn(submissions(11L, 22));
        when(milestoneRepository.claim(7L, 11L, MotivationMilestoneService.FINAL_STRETCH))
                .thenReturn(0);

        service.evaluateAfterSubmission(7L, 11L, LocalDate.parse("2026-07-22"), false);

        verify(notificationRepository, never()).saveAndFlush(any());
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void directFirstPlaceClaimsTopThreeSilentlyAndSendsOnlyFirstPlace() throws Exception {
        Challenge challenge = challenge(12L, true, LocalDate.parse("2026-07-22"), 30);
        stubEligibleParticipant(challenge, true);
        ChallengeCrewLifecycle lifecycle = new ChallengeCrewLifecycle();
        lifecycle.setChallengeId(12L);
        lifecycle.setStartedAt(Timestamp.from(Instant.parse("2026-07-22T00:00:00Z")));
        when(lifecycleRepository.findById(12L)).thenReturn(Optional.of(lifecycle));
        when(submissionRepository.findActiveByUserIdAndChallengeId(7L, 12L))
                .thenReturn(submissions(12L, 1));
        when(leaderboardService.getUserRank(12L, 7L, 7L)).thenReturn(
                LeaderboardResponse.UserRankDTO.builder()
                        .userId(7L)
                        .rank(1L)
                        .totalParticipants(4L)
                        .score(10.0)
                        .build()
        );
        when(milestoneRepository.claim(7L, 12L, MotivationMilestoneService.TOP_THREE))
                .thenReturn(1);
        when(milestoneRepository.claim(7L, 12L, MotivationMilestoneService.FIRST_PLACE))
                .thenReturn(1);

        service.evaluateAfterSubmission(7L, 12L, LocalDate.parse("2026-07-22"), true);

        verify(milestoneRepository).claim(7L, 12L, MotivationMilestoneService.TOP_THREE);
        verify(milestoneRepository).claim(7L, 12L, MotivationMilestoneService.FIRST_PLACE);
        Notification saved = notificationFromSave();
        assertEquals("You're #1 in Private crew. Keep showing up to hold the lead.", saved.getMessage());
        assertTrue(saved.getActionObject().contains("\"milestoneKind\":\"FIRST_PLACE\""));
        assertTrue(saved.getActionObject().contains("\"rank\":1"));
        assertTrue(saved.getActionObject().contains("\"totalParticipants\":4"));
        verify(notificationRepository).saveAndFlush(any(Notification.class));
    }

    @Test
    void motivationOptOutPreventsClaimsAndInboxEvenWithoutConsideringPushState() {
        Challenge challenge = challenge(11L, false, LocalDate.parse("2026-07-01"), 30);
        stubEligibleParticipant(challenge, false);
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(7L);
        preferences.setNotifyOnMotivation(false);
        preferences.setPushNotificationsEnabled(true);
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.of(preferences));

        service.evaluateAfterSubmission(7L, 11L, LocalDate.parse("2026-07-22"), false);

        verify(submissionRepository, never()).findActiveByUserIdAndChallengeId(any(), any());
        verify(milestoneRepository, never()).claim(any(), any(), anyString());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void missingPreferenceRowInheritsDisabledLegacyPushAsMotivationOptOut() {
        Challenge challenge = challenge(11L, false, LocalDate.parse("2026-07-01"), 30);
        stubEligibleParticipant(challenge, false);
        User user = new User();
        user.setId(7L);
        user.setActive(true);
        user.setNotifications(0);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.evaluateAfterSubmission(7L, 11L, LocalDate.parse("2026-07-22"), false);

        verify(submissionRepository, never()).findActiveByUserIdAndChallengeId(any(), any());
        verify(milestoneRepository, never()).claim(any(), any(), anyString());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void completedParticipantCannotReceiveProgressOrRankMilestones() throws Exception {
        Challenge challenge = challenge(12L, true, LocalDate.parse("2026-07-22"), 30);
        stubEligibleParticipant(challenge, true);
        UserChallenge completed = participation(7L, 12L);
        completed.setCompleted(true);
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 12L))
                .thenReturn(Optional.of(completed));

        service.evaluateAfterSubmission(7L, 12L, LocalDate.parse("2026-07-22"), true);

        verify(leaderboardService, never()).getUserRank(any(), any(), any());
        verify(milestoneRepository, never()).claim(any(), any(), anyString());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void eveningFinishStrongClaimIsOnceOnlyWithoutTokenOrGlobalPush() throws Exception {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(7L);
        preferences.setNotifyOnMotivation(true);
        preferences.setPushNotificationsEnabled(false);
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.of(preferences));
        when(reminderRepository.findMotivationUsers()).thenReturn(List.of(eveningUser(7L, "UTC")));
        when(reminderRepository.findPendingForUser(7L, LocalDate.parse("2026-07-22")))
                .thenReturn(List.of(reminderCandidate(
                        21L, "Morning run", "DAILY", false, false,
                        LocalDate.parse("2026-07-01"), 30, 22, 0)));
        when(milestoneRepository.claim(7L, 21L, MotivationMilestoneService.FINISH_STRONG))
                .thenReturn(1, 0);

        Set<Long> first = service.evaluateEveningProgressMilestones();
        Set<Long> second = service.evaluateEveningProgressMilestones();

        assertEquals(Set.of(7L), first);
        assertTrue(second.isEmpty());
        verify(milestoneRepository, org.mockito.Mockito.times(2))
                .claim(7L, 21L, MotivationMilestoneService.FINISH_STRONG);
        verify(notificationRepository).saveAndFlush(any(Notification.class));
        Notification saved = notificationFromSave();
        assertTrue(saved.getMessage().startsWith("Finish strong:"));
        verify(leaderboardService, never()).getUserRank(any(), any(), any());
    }

    @Test
    void eveningPrePassClaimsFinalStretchWithoutTodaysSubmission() throws Exception {
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(reminderRepository.findMotivationUsers()).thenReturn(List.of(eveningUser(7L, "UTC")));
        when(reminderRepository.findPendingForUser(7L, LocalDate.parse("2026-07-22")))
                .thenReturn(List.of(reminderCandidate(
                        22L, "Read daily", "DAILY", false, false,
                        LocalDate.parse("2026-07-01"), 30, 22, 21)));
        when(milestoneRepository.claim(7L, 22L, MotivationMilestoneService.FINAL_STRETCH))
                .thenReturn(1);

        Set<Long> result = service.evaluateEveningProgressMilestones();

        assertEquals(Set.of(7L), result);
        verify(milestoneRepository).claim(7L, 22L, MotivationMilestoneService.FINAL_STRETCH);
        verify(leaderboardService, never()).getUserRank(any(), any(), any());
    }

    @Test
    void eveningPrePassSkipsOutsideLocalWindow() {
        when(reminderRepository.findMotivationUsers())
                .thenReturn(List.of(eveningUser(7L, "Asia/Kolkata")));

        Set<Long> result = service.evaluateEveningProgressMilestones();

        assertTrue(result.isEmpty());
        verify(preferencesRepository, never()).findByUserId(any());
        verify(reminderRepository, never()).findPendingForUser(any(), any());
    }

    @Test
    void eveningPrePassHonorsMotivationOptOut() {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(7L);
        preferences.setNotifyOnMotivation(false);
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.of(preferences));
        when(reminderRepository.findMotivationUsers()).thenReturn(List.of(eveningUser(7L, "UTC")));

        Set<Long> result = service.evaluateEveningProgressMilestones();

        assertTrue(result.isEmpty());
        verify(reminderRepository, never()).findPendingForUser(any(), any());
        verify(milestoneRepository, never()).claim(any(), any(), anyString());
    }

    @Test
    void eveningPrePassDoesNotClaimOneOffOrPrivateLobbyMilestones() {
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(reminderRepository.findMotivationUsers()).thenReturn(List.of(eveningUser(7L, "UTC")));
        MotivationReminderRepository.PendingMotivationProjection oneOff = reminderCandidate(
                31L, "One off", "ONE_OFF", false, false,
                LocalDate.parse("2026-07-22"), 1, 1, 0);
        MotivationReminderRepository.PendingMotivationProjection privateLobby = reminderCandidate(
                32L, "Private lobby", "DAILY", true, true,
                LocalDate.parse("2026-07-01"), 30, 22, 0);
        when(reminderRepository.findPendingForUser(7L, LocalDate.parse("2026-07-22")))
                .thenReturn(List.of(oneOff, privateLobby));

        Set<Long> result = service.evaluateEveningProgressMilestones();

        assertTrue(result.isEmpty());
        verify(milestoneRepository, never()).claim(any(), any(), anyString());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void rankLookupRuntimeFailureDoesNotPreventProgressMilestone() throws Exception {
        Challenge challenge = challenge(41L, true, LocalDate.parse("2026-07-01"), 30);
        stubEligibleParticipant(challenge, true);
        ChallengeCrewLifecycle lifecycle = new ChallengeCrewLifecycle();
        lifecycle.setChallengeId(41L);
        lifecycle.setStartedAt(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
        when(lifecycleRepository.findById(41L)).thenReturn(Optional.of(lifecycle));
        when(submissionRepository.findActiveByUserIdAndChallengeId(7L, 41L))
                .thenReturn(submissions(41L, 22));
        when(leaderboardService.getUserRank(41L, 7L, 7L))
                .thenThrow(new IllegalStateException("leaderboard unavailable"));
        when(milestoneRepository.claim(7L, 41L, MotivationMilestoneService.FINAL_STRETCH))
                .thenReturn(1);

        service.evaluateAfterSubmission(7L, 41L, LocalDate.parse("2026-07-22"), true);

        verify(notificationRepository).saveAndFlush(any(Notification.class));
        verify(milestoneRepository).claim(7L, 41L, MotivationMilestoneService.FINAL_STRETCH);
    }

    private void stubEligibleParticipant(Challenge challenge, boolean motivationPreferencePresent) {
        when(challengeRepository.findById(challenge.getId())).thenReturn(Optional.of(challenge));
        User user = new User();
        user.setId(7L);
        user.setActive(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, challenge.getId()))
                .thenReturn(Optional.of(participation(7L, challenge.getId())));
        if (motivationPreferencePresent) {
            UserPreferences preferences = new UserPreferences();
            preferences.setUserId(7L);
            preferences.setNotifyOnMotivation(true);
            when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.of(preferences));
        } else {
            when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.empty());
        }
    }

    private Challenge challenge(Long id, boolean privateChallenge, LocalDate startDate, int totalDays) {
        Challenge challenge = new Challenge();
        challenge.setId(id);
        challenge.setTitle(privateChallenge ? "Private crew" : "Morning run");
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        challenge.setActive(true);
        challenge.setIsPrivate(privateChallenge);
        challenge.setStartDate(startDate);
        challenge.setNumberOfDays(totalDays);
        return challenge;
    }

    private UserChallenge participation(Long userId, Long challengeId) {
        UserChallenge participation = new UserChallenge();
        participation.setUserId(userId);
        participation.setChallengeId(challengeId);
        participation.setActive(true);
        participation.setBanned(false);
        participation.setCompleted(false);
        return participation;
    }

    private List<Submission> submissions(Long challengeId, int completedDays) {
        return IntStream.rangeClosed(1, completedDays)
                .mapToObj(day -> {
                    Submission submission = new Submission();
                    submission.setUserId(7L);
                    submission.setChallengeId(challengeId);
                    submission.setDayNumber(day);
                    submission.setActive(true);
                    return submission;
                })
                .toList();
    }

    private MotivationReminderRepository.EveningUserProjection eveningUser(Long userId, String timezone) {
        return new EveningUser(userId, timezone);
    }

    private MotivationReminderRepository.PendingMotivationProjection reminderCandidate(
            Long challengeId,
            String title,
            String challengeMode,
            boolean privateChallenge,
            boolean privateLobby,
            LocalDate startDate,
            int totalDays,
            int dayNumber,
            int completedDays
    ) {
        return new ReminderCandidate(
                challengeId,
                title,
                challengeMode,
                privateChallenge,
                privateLobby,
                startDate,
                totalDays,
                dayNumber,
                completedDays
        );
    }

    private record EveningUser(Long userId, String timezone)
            implements MotivationReminderRepository.EveningUserProjection {
        @Override
        public Long getUserId() {
            return userId;
        }

        @Override
        public String getTimezone() {
            return timezone;
        }
    }

    private record ReminderCandidate(
            Long challengeId,
            String challengeTitle,
            String challengeMode,
            Boolean privateChallenge,
            Boolean privateLobby,
            LocalDate startDate,
            Integer totalDays,
            Integer dayNumber,
            Integer completedDays
    ) implements MotivationReminderRepository.PendingMotivationProjection {
        @Override
        public Long getChallengeId() {
            return challengeId;
        }

        @Override
        public String getChallengeTitle() {
            return challengeTitle;
        }

        @Override
        public LocalDate getStartDate() {
            return startDate;
        }

        @Override
        public Integer getTotalDays() {
            return totalDays;
        }

        @Override
        public String getChallengeMode() {
            return challengeMode;
        }

        @Override
        public Boolean getChallengeActive() {
            return true;
        }

        @Override
        public Boolean getParticipantCompleted() {
            return false;
        }

        @Override
        public Integer getDayNumber() {
            return dayNumber;
        }

        @Override
        public Integer getCompletedDays() {
            return completedDays;
        }

        @Override
        public Boolean getPrivateChallenge() {
            return privateChallenge;
        }

        @Override
        public Boolean getPrivateLobby() {
            return privateLobby;
        }

        @Override
        public Long getRank() {
            return null;
        }

        @Override
        public Long getTotalParticipants() {
            return null;
        }
    }

    private Notification notificationFromSave() {
        var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification notification = captor.getValue();
        assertNotNull(notification.getId());
        return notification;
    }
}
