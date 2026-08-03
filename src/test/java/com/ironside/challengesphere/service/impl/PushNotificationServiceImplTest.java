package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.RegisterPushTokenRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.MotivationReminderRepository;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.PushNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushNotificationServiceImplTest {

    private PushDeviceTokenRepository tokenRepository;
    private SubmissionRepository submissionRepository;
    private CapturingSender sender;
    private NotificationQuotaService notificationQuotaService;
    private PushNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        tokenRepository = mock(PushDeviceTokenRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        sender = new CapturingSender();
        notificationQuotaService = mock(NotificationQuotaService.class);
        service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.fixed(Instant.parse("2026-07-07T14:45:00Z"), ZoneOffset.UTC)
        );
        ReflectionTestUtils.setField(service, "notificationQuotaService", notificationQuotaService);
        when(notificationQuotaService.reserve(anyLong())).thenAnswer(invocation ->
                Optional.of(reservation(invocation.getArgument(0), false)));
        when(notificationQuotaService.reserve(anyLong(), any(String.class))).thenAnswer(invocation ->
                Optional.of(reservation(invocation.getArgument(0), true)));
        when(notificationQuotaService.reserve(anyLong(), any(LocalDate.class))).thenAnswer(invocation ->
                Optional.of(reservation(invocation.getArgument(0), false)));
        when(notificationQuotaService.reserve(anyLong(), any(LocalDate.class), any(String.class))).thenAnswer(invocation ->
                Optional.of(reservation(invocation.getArgument(0), true)));
    }

    @Test
    void registerTokenAcceptsExpoTokenAndEnablesIt() throws Exception {
        RegisterPushTokenRequest request = new RegisterPushTokenRequest();
        request.setExpoPushToken("ExpoPushToken[test-token]");
        request.setPlatform("Android");
        request.setTimezone("Asia/Kolkata");

        when(tokenRepository.findByExpoPushToken("ExpoPushToken[test-token]")).thenReturn(Optional.empty());
        when(tokenRepository.save(any(PushDeviceToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PushDeviceToken token = service.registerToken(42L, request);

        assertEquals(42L, token.getUserId());
        assertEquals("ExpoPushToken[test-token]", token.getExpoPushToken());
        assertEquals("android", token.getPlatform());
        assertEquals("Asia/Kolkata", token.getTimezone());
        assertTrue(token.getEnabled());
        assertEquals(Timestamp.from(Instant.parse("2026-07-07T14:45:00Z")), token.getLastSeenAt());
    }

    @Test
    void registerTokenClearsReminderMarkerWhenTokenMovesToAnotherUser() throws Exception {
        PushDeviceToken existing = token("ExpoPushToken[shared-device]");
        existing.setUserId(7L);
        existing.setLastDailyReminderSentDate(LocalDate.parse("2026-07-07"));

        RegisterPushTokenRequest request = new RegisterPushTokenRequest();
        request.setExpoPushToken(existing.getExpoPushToken());
        request.setPlatform("ios");
        request.setTimezone("Asia/Kolkata");

        when(tokenRepository.findByExpoPushToken(existing.getExpoPushToken()))
                .thenReturn(Optional.of(existing));
        when(tokenRepository.save(existing)).thenReturn(existing);

        PushDeviceToken reassigned = service.registerToken(42L, request);

        assertEquals(42L, reassigned.getUserId());
        assertNull(reassigned.getLastDailyReminderSentDate());
    }

    @Test
    void registerTokenRejectsInvalidExpoToken() {
        RegisterPushTokenRequest request = new RegisterPushTokenRequest();
        request.setExpoPushToken("not-an-expo-token");
        request.setPlatform("ios");
        request.setTimezone("UTC");

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> service.registerToken(42L, request)
        );

        assertEquals("Invalid Expo push token", exception.getResponseMessage());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void sendInteractionPushUsesFinalEligibilityQueryAndOneQuotaReservation() {
        PushDeviceToken first = token("ExpoPushToken[first]");
        PushDeviceToken second = token("ExponentPushToken[second]");
        when(tokenRepository.findEligibleByUserIdAndType(42L, "GLORY"))
                .thenReturn(List.of(first, second));

        service.sendInteractionPush(42L, "GLORY", "Someone liked your submission", "{\"submissionId\":7}");

        assertEquals(2, sender.messages.size());
        assertEquals("New glory ✨", sender.messages.get(0).title());
        assertEquals("Someone liked your submission 🙌", sender.messages.get(0).body());
        assertEquals("GLORY", sender.messages.get(0).data().get("type"));
        assertEquals(7, sender.messages.get(0).data().get("submissionId"));
        verify(tokenRepository).findEligibleByUserIdAndType(42L, "GLORY");
        verify(notificationQuotaService, times(1)).reserve(42L, LocalDate.parse("2026-07-07"));
        verify(notificationQuotaService, never()).release(any());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "FOLLOW|New follower 👋|A notification 👋",
            "GLORY|New glory ✨|A notification 🙌",
            "COMMENT|New comment 💬|A notification 💬",
            "CHALLENGE_INVITE|Challenge invite 🎯|A notification 🔥"
    })
    void interactionPushesUseEventSpecificEmoji(
            String type,
            String expectedTitle,
            String expectedBody
    ) {
        when(tokenRepository.findEligibleByUserIdAndType(42L, type))
                .thenReturn(List.of(token("ExpoPushToken[emoji]")));

        service.sendInteractionPush(42L, type, "A notification", "{}");

        assertEquals(expectedTitle, sender.messages.get(0).title());
        assertEquals(expectedBody, sender.messages.get(0).body());
    }

    @Test
    void sendInteractionPushDoesNotReserveQuotaWithoutEligibleTokens() {
        when(tokenRepository.findEligibleByUserIdAndType(42L, "COMMENT")).thenReturn(List.of());

        service.sendInteractionPush(42L, "COMMENT", "Someone commented", "{}");

        assertTrue(sender.messages.isEmpty());
        verify(notificationQuotaService, never()).reserve(anyLong());
    }

    @Test
    void sendInteractionPushStopsWhenNineteenthNotificationQuotaIsExhausted() {
        when(tokenRepository.findEligibleByUserIdAndType(42L, "FOLLOW"))
                .thenReturn(List.of(token("ExpoPushToken[follower]")));
        when(notificationQuotaService.reserve(42L, LocalDate.parse("2026-07-07"))).thenReturn(Optional.empty());

        service.sendInteractionPush(42L, "FOLLOW", "Someone followed you", "{}");

        assertTrue(sender.messages.isEmpty());
        verify(notificationQuotaService, times(1)).reserve(42L, LocalDate.parse("2026-07-07"));
    }

    @Test
    void sendInteractionPushDisablesDeviceNotRegisteredToken() {
        PushDeviceToken valid = token("ExpoPushToken[valid]");
        PushDeviceToken invalid = token("ExpoPushToken[invalid]");
        when(tokenRepository.findEligibleByUserIdAndType(42L, "FOLLOW"))
                .thenReturn(List.of(valid, invalid));
        sender.result = new PushNotificationSender.SendResult(
                Set.of(valid.getExpoPushToken()),
                Map.of(invalid.getExpoPushToken(), "DeviceNotRegistered")
        );

        service.sendInteractionPush(42L, "FOLLOW", "Someone followed you", "{}");

        verify(tokenRepository).disableTokenByExpoPushToken(invalid.getExpoPushToken());
        verify(notificationQuotaService, never()).release(any());
    }

    @Test
    void sendInteractionPushReleasesQuotaWhenSenderThrows() {
        PushDeviceToken recipientToken = token("ExpoPushToken[recipient]");
        NotificationQuotaService.Reservation reservation = reservation(42L, false);
        when(tokenRepository.findEligibleByUserIdAndType(42L, "COMMENT"))
                .thenReturn(List.of(recipientToken));
        when(notificationQuotaService.reserve(42L, LocalDate.parse("2026-07-07"))).thenReturn(Optional.of(reservation));
        sender.failure = new IllegalStateException("sender unavailable");

        service.sendInteractionPush(42L, "COMMENT", "Someone commented", "{}");

        verify(notificationQuotaService).release(reservation);
    }

    @Test
    void sendInteractionPushReleasesQuotaWhenSenderReturnsNoResult() {
        PushDeviceToken recipientToken = token("ExpoPushToken[recipient]");
        NotificationQuotaService.Reservation reservation = reservation(42L, false);
        when(tokenRepository.findEligibleByUserIdAndType(42L, "COMMENT"))
                .thenReturn(List.of(recipientToken));
        when(notificationQuotaService.reserve(42L, LocalDate.parse("2026-07-07"))).thenReturn(Optional.of(reservation));
        sender.returnNull = true;

        service.sendInteractionPush(42L, "COMMENT", "Someone commented", "{}");

        verify(notificationQuotaService).release(reservation);
    }

    @Test
    void nonPlanMilestoneDoesNotSendAnImmediatePush() {
        PushDeviceToken recipientToken = token("ExpoPushToken[momentum]");
        recipientToken.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEligibleByUserIdAndType(42L, "MOMENTUM_MILESTONE"))
                .thenReturn(List.of(recipientToken));

        service.sendInteractionPush(
                42L,
                "MOMENTUM_MILESTONE",
                "You're #1 in Morning run.",
                "{\"challengeId\":11,\"challengeTitle\":\"Morning run\","
                        + "\"milestoneKind\":\"FIRST_PLACE\",\"rank\":1,"
                        + "\"totalParticipants\":4,\"notificationId\":91}"
        );

        assertTrue(sender.messages.isEmpty());
        verify(notificationQuotaService, never()).reserve(anyLong(), any(LocalDate.class));
    }

    @Test
    void momentumMilestoneDoesNotSendAfterTheDailyGateWasAlreadyUsed() {
        PushDeviceToken recipientToken = token("ExpoPushToken[momentum]");
        recipientToken.setTimezone("Asia/Kolkata");
        recipientToken.setLastDailyReminderSentDate(LocalDate.parse("2026-07-07"));
        when(tokenRepository.findEligibleByUserIdAndType(42L, "MOMENTUM_MILESTONE"))
                .thenReturn(List.of(recipientToken));

        service.sendInteractionPush(
                42L,
                "MOMENTUM_MILESTONE",
                "Final stretch",
                "{\"milestoneKind\":\"FINAL_STRETCH\"}"
        );

        assertTrue(sender.messages.isEmpty());
        verify(notificationQuotaService, never()).reserve(anyLong(), any(String.class));
        verify(tokenRepository, never()).saveAll(any());
    }

    @Test
    void dailyReminderSendsOncePerLocalDay() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setUserId(42L);
        token.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(
                        pendingChallenge(11L, "Morning run", 4),
                        pendingChallenge(12L, "Read daily", 2),
                        pendingChallenge(13L, "Meditate", 7)
                ));

        int sent = service.sendDailyUploadReminders();

        assertEquals(1, sent);
        assertEquals(1, sender.messages.size());
        assertEquals("FINISH_STRONG_REMINDER", sender.messages.get(0).data().get("type"));
        assertEquals(11L, sender.messages.get(0).data().get("challengeId"));
        assertEquals("Morning run", sender.messages.get(0).data().get("challengeTitle"));
        assertEquals("FINISH_STRONG_REMINDER", sender.messages.get(0).data().get("milestoneKind"));
        assertEquals("Your streak is waiting 🔥", sender.messages.get(0).title());
        assertTrue(sender.messages.get(0).body().contains("3 challenges need your check-in today"));
        assertEquals(sender.messages.get(0).collapseId(), sender.messages.get(0).tag());
        assertEquals(24 * 60 * 60, sender.messages.get(0).ttl());
        assertEquals(LocalDate.parse("2026-07-07"), token.getLastDailyReminderSentDate());
        verify(tokenRepository).saveAll(List.of(token));
        verify(notificationQuotaService).reserve(
                42L, LocalDate.parse("2026-07-07"), "FINISH_STRONG_REMINDER:2026-07-07");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-07-07T03:30:00Z,PENDING_UPLOAD_REMINDER,Upload reminder 📸",
            "2026-07-07T12:00:00Z,LEADERBOARD_MOTIVATION,Keep climbing 🏆",
            "2026-07-07T14:45:00Z,FINISH_STRONG_REMINDER,Your streak is waiting 🔥"
    })
    void scheduledTimesUseTheUsersTimezone(String instant, String expectedType, String expectedTitle) {
        PushDeviceToken reminderToken = token("ExpoPushToken[scheduled]");
        reminderToken.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(reminderToken));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(pendingChallenge(11L, "Morning run", 4)));

        service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
        );
        ReflectionTestUtils.setField(service, "notificationQuotaService", notificationQuotaService);

        assertEquals(1, service.sendDailyUploadReminders());
        assertEquals(expectedType, sender.messages.get(0).data().get("type"));
        assertEquals(expectedTitle, sender.messages.get(0).title());
    }

    @Test
    void completedUploadsCancelPendingReminder() {
        PushDeviceToken reminderToken = token("ExpoPushToken[complete]");
        reminderToken.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(reminderToken));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07"))).thenReturn(List.of());

        assertEquals(0, service.sendDailyUploadReminders());
        assertTrue(sender.messages.isEmpty());
    }

    @Test
    void enrichedReminderPrioritizesFinalDayAndSuppressesZeroSubmissionRank() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));

        MotivationReminderRepository motivationRepository = mock(MotivationReminderRepository.class);
        ReflectionTestUtils.setField(service, "motivationReminderRepository", motivationRepository);
        ReflectionTestUtils.setField(service, "momentumPolicy", new ChallengeMomentumPolicy());
        MotivationReminderRepository.ScheduledMotivationProjection regular = motivationCandidate(
                10L, "Read daily", LocalDate.parse("2026-07-01"),
                10, 7, 4, false, null, null);
        MotivationReminderRepository.ScheduledMotivationProjection finalDay = motivationCandidate(
                20L, "Private crew", LocalDate.parse("2026-06-28"),
                10, 10, 0, true, 1L, 4L);
        when(motivationRepository.findAllPending(any(Timestamp.class)))
                .thenReturn(List.of(regular, finalDay));

        int sent = service.sendDailyUploadReminders();

        assertEquals(1, sent);
        assertEquals(20L, sender.messages.get(0).data().get("challengeId"));
        assertEquals("Finish strong 💪", sender.messages.get(0).title());
        assertEquals(
                "Upload your first check-in in Private crew to join the leaderboard. "
                        + "1 other challenge is waiting today.",
                sender.messages.get(0).body()
        );
        assertFalse(sender.messages.get(0).data().containsKey("rank"));
        assertFalse(sender.messages.get(0).data().containsKey("totalParticipants"));
        verify(submissionRepository, never()).findIncompleteDailyChallengesForUser(any(), any());
    }

    @Test
    void enrichedReminderUsesPrivateRankWhenAtLeastTwoParticipantsAreRanked() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));

        MotivationReminderRepository motivationRepository = mock(MotivationReminderRepository.class);
        ReflectionTestUtils.setField(service, "motivationReminderRepository", motivationRepository);
        ReflectionTestUtils.setField(service, "momentumPolicy", new ChallengeMomentumPolicy());
        MotivationReminderRepository.ScheduledMotivationProjection privateRanked = motivationCandidate(
                21L, "Private crew", LocalDate.parse("2026-07-01"),
                10, 7, 4, true, 2L, 5L);
        when(motivationRepository.findAllPending(any(Timestamp.class)))
                .thenReturn(List.of(privateRanked));

        service.sendDailyUploadReminders();

        assertTrue(sender.messages.get(0).body().contains("You're #2 in your crew"));
        assertEquals(2L, sender.messages.get(0).data().get("rank"));
        assertEquals(5L, sender.messages.get(0).data().get("totalParticipants"));
        assertEquals(4, sender.messages.get(0).data().get("completedDays"));
    }

    @Test
    void scheduledNotificationsStopAtTwentyOneLocal() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setUserId(42L);
        token.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));
        service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.fixed(Instant.parse("2026-07-07T15:30:00Z"), ZoneOffset.UTC)
        );
        ReflectionTestUtils.setField(service, "notificationQuotaService", notificationQuotaService);

        int sent = service.sendDailyUploadReminders();

        assertEquals(0, sent);
        assertTrue(sender.messages.isEmpty());
        verify(submissionRepository, never()).findIncompleteDailyChallengesForUser(any(), any());
        verify(tokenRepository, never()).saveAll(any());
    }

    @Test
    void dailyReminderReservesOneLogicalNotificationAcrossMultipleDevices() {
        PushDeviceToken first = token("ExpoPushToken[first]");
        PushDeviceToken second = token("ExpoPushToken[second]");
        first.setTimezone("Asia/Kolkata");
        second.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(first, second));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(
                        pendingChallenge(21L, "Walk", 1),
                        pendingChallenge(22L, "Read", 1)
                ));

        int sent = service.sendDailyUploadReminders();

        assertEquals(2, sent);
        assertEquals(2, sender.messages.size());
        verify(notificationQuotaService, times(1))
                .reserve(42L, LocalDate.parse("2026-07-07"), "FINISH_STRONG_REMINDER:2026-07-07");
        verify(submissionRepository, times(1))
                .findIncompleteDailyChallengesForUser(42L, LocalDate.parse("2026-07-07"));
    }

    @Test
    void dailyReminderStopsWhenDailyNotificationQuotaIsExhausted() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(pendingChallenge(31L, "Walk", 1)));
        when(notificationQuotaService.reserve(
                42L, LocalDate.parse("2026-07-07"), "FINISH_STRONG_REMINDER:2026-07-07"))
                .thenReturn(Optional.empty());

        int sent = service.sendDailyUploadReminders();

        assertEquals(0, sent);
        assertTrue(sender.messages.isEmpty());
        verify(tokenRepository, never()).saveAll(any());
    }

    @Test
    void dailyReminderReleasesReservationAndLeavesMarkerUnsetWhenSenderRejectsIt() {
        PushDeviceToken reminderToken = token("ExpoPushToken[reminder]");
        reminderToken.setTimezone("Asia/Kolkata");
        NotificationQuotaService.Reservation reservation = reservation(42L, true);
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(reminderToken));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(pendingChallenge(41L, "Walk", 3)));
        when(notificationQuotaService.reserve(
                42L, LocalDate.parse("2026-07-07"), "FINISH_STRONG_REMINDER:2026-07-07"))
                .thenReturn(Optional.of(reservation));
        sender.result = new PushNotificationSender.SendResult(
                Set.of(),
                Map.of(reminderToken.getExpoPushToken(), "REQUEST_FAILED")
        );

        int sent = service.sendDailyUploadReminders();

        assertEquals(0, sent);
        assertNull(reminderToken.getLastDailyReminderSentDate());
        verify(notificationQuotaService).release(reservation);
        verify(tokenRepository, never()).saveAll(any());
    }

    @Test
    void dailyReminderDoesNotReenableDeviceNotRegisteredTokenWhenAnotherDeviceIsAccepted() {
        PushDeviceToken valid = token("ExpoPushToken[valid-reminder]");
        PushDeviceToken invalid = token("ExpoPushToken[invalid-reminder]");
        valid.setTimezone("Asia/Kolkata");
        invalid.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(valid, invalid));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(pendingChallenge(51L, "Walk", 1)));
        sender.result = new PushNotificationSender.SendResult(
                Set.of(valid.getExpoPushToken()),
                Map.of(invalid.getExpoPushToken(), "DeviceNotRegistered")
        );

        int sent = service.sendDailyUploadReminders();

        assertEquals(1, sent);
        assertEquals(LocalDate.parse("2026-07-07"), valid.getLastDailyReminderSentDate());
        assertNull(invalid.getLastDailyReminderSentDate());
        verify(tokenRepository).disableTokenByExpoPushToken(invalid.getExpoPushToken());
        verify(tokenRepository).saveAll(List.of(valid));
    }

    @Test
    void dailyReminderReleasesEarlierReservationsWhenLaterAssemblyFails() {
        PushDeviceToken firstUserToken = token("ExpoPushToken[first-user]");
        PushDeviceToken secondUserToken = token("ExpoPushToken[second-user]");
        firstUserToken.setTimezone("Asia/Kolkata");
        secondUserToken.setUserId(43L);
        secondUserToken.setTimezone("Asia/Kolkata");
        NotificationQuotaService.Reservation firstReservation = reservation(42L, true);
        when(tokenRepository.findEnabledReminderTokens())
                .thenReturn(List.of(firstUserToken, secondUserToken));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                42L, LocalDate.parse("2026-07-07")))
                .thenReturn(List.of(pendingChallenge(61L, "Walk", 1)));
        when(submissionRepository.findIncompleteDailyChallengesForUser(
                43L, LocalDate.parse("2026-07-07")))
                .thenThrow(new IllegalStateException("query failed"));
        when(notificationQuotaService.reserve(
                42L, LocalDate.parse("2026-07-07"), "FINISH_STRONG_REMINDER:2026-07-07"))
                .thenReturn(Optional.of(firstReservation));

        int sent = service.sendDailyUploadReminders();

        assertEquals(0, sent);
        assertTrue(sender.messages.isEmpty());
        verify(notificationQuotaService).release(firstReservation);
        verify(tokenRepository, never()).saveAll(any());
    }

    private PushDeviceToken token(String value) {
        PushDeviceToken token = new PushDeviceToken();
        token.setUserId(42L);
        token.setExpoPushToken(value);
        token.setPlatform("ios");
        token.setTimezone("UTC");
        token.setEnabled(true);
        token.setLastSeenAt(Timestamp.from(Instant.parse("2026-07-07T14:45:00Z")));
        return token;
    }

    private static NotificationQuotaService.Reservation reservation(Long userId, boolean deduplicated) {
        return new NotificationQuotaService.Reservation(
                userId,
                "notification-quota:user:{" + userId + "}",
                "notification-quota:dedupe:{" + userId + "}:test",
                "member-" + userId,
                deduplicated
        );
    }

    private SubmissionRepository.PendingDailyChallengeProjection pendingChallenge(
            Long challengeId,
            String challengeTitle,
            Integer dayNumber
    ) {
        return new PendingChallenge(challengeId, challengeTitle, dayNumber);
    }

    private MotivationReminderRepository.ScheduledMotivationProjection motivationCandidate(
            Long challengeId,
            String challengeTitle,
            LocalDate startDate,
            Integer totalDays,
            Integer dayNumber,
            Integer completedDays,
            Boolean privateChallenge,
            Long rank,
            Long totalParticipants
    ) {
        MotivationReminderRepository.ScheduledMotivationProjection projection =
                mock(MotivationReminderRepository.ScheduledMotivationProjection.class);
        when(projection.getUserId()).thenReturn(42L);
        when(projection.getTimezone()).thenReturn("Asia/Kolkata");
        when(projection.getLocalDate()).thenReturn(LocalDate.parse("2026-07-07"));
        when(projection.getChallengeId()).thenReturn(challengeId);
        when(projection.getChallengeTitle()).thenReturn(challengeTitle);
        when(projection.getStartDate()).thenReturn(startDate);
        when(projection.getTotalDays()).thenReturn(totalDays);
        when(projection.getChallengeMode()).thenReturn("DAILY");
        when(projection.getChallengeActive()).thenReturn(true);
        when(projection.getParticipantCompleted()).thenReturn(false);
        when(projection.getDayNumber()).thenReturn(dayNumber);
        when(projection.getCompletedDays()).thenReturn(completedDays);
        when(projection.getPrivateChallenge()).thenReturn(privateChallenge);
        when(projection.getPrivateLobby()).thenReturn(false);
        when(projection.getRank()).thenReturn(rank);
        when(projection.getTotalParticipants()).thenReturn(totalParticipants);
        return projection;
    }

    private record PendingChallenge(
            Long challengeId,
            String challengeTitle,
            Integer dayNumber
    ) implements SubmissionRepository.PendingDailyChallengeProjection {
        @Override
        public Long getChallengeId() {
            return challengeId;
        }

        @Override
        public String getChallengeTitle() {
            return challengeTitle;
        }

        @Override
        public Integer getDayNumber() {
            return dayNumber;
        }
    }

    private static final class CapturingSender implements PushNotificationSender {
        private final List<PushMessage> messages = new ArrayList<>();
        private SendResult result;
        private RuntimeException failure;
        private boolean returnNull;

        @Override
        public SendResult send(List<PushMessage> messages) {
            this.messages.addAll(messages);
            if (failure != null) {
                throw failure;
            }
            if (returnNull) {
                return null;
            }
            if (result != null) {
                return result;
            }
            Set<String> acceptedTokens = messages.stream()
                    .map(PushMessage::to)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new SendResult(acceptedTokens, Map.of());
        }
    }
}
