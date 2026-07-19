package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.RegisterPushTokenRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.PushNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushNotificationServiceImplTest {

    private PushDeviceTokenRepository tokenRepository;
    private SubmissionRepository submissionRepository;
    private CapturingSender sender;
    private PushNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        tokenRepository = mock(PushDeviceTokenRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        sender = new CapturingSender();
        service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.fixed(Instant.parse("2026-07-07T13:30:00Z"), ZoneOffset.UTC)
        );
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
        assertEquals(Timestamp.from(Instant.parse("2026-07-07T13:30:00Z")), token.getLastSeenAt());
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
    void sendInteractionPushSendsToAllEnabledUserTokens() {
        PushDeviceToken first = token("ExpoPushToken[first]");
        PushDeviceToken second = token("ExponentPushToken[second]");
        when(tokenRepository.findEnabledByUserId(42L)).thenReturn(List.of(first, second));

        service.sendInteractionPush(42L, "GLORY", "Someone liked your submission", "{\"submissionId\":7}");

        assertEquals(2, sender.messages.size());
        assertEquals("New like", sender.messages.get(0).title());
        assertEquals("GLORY", sender.messages.get(0).data().get("type"));
        assertEquals(7, sender.messages.get(0).data().get("submissionId"));
    }

    @Test
    void dailyReminderSendsOncePerLocalDay() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setUserId(42L);
        token.setTimezone("Asia/Kolkata");
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));
        when(submissionRepository.existsIncompleteDailyChallengeForUser(42L, LocalDate.parse("2026-07-07")))
                .thenReturn(true);

        int sent = service.sendDailyUploadReminders();

        assertEquals(1, sent);
        assertEquals(1, sender.messages.size());
        assertEquals("DAILY_UPLOAD_REMINDER", sender.messages.get(0).data().get("type"));
        assertEquals(LocalDate.parse("2026-07-07"), token.getLastDailyReminderSentDate());
        verify(tokenRepository).saveAll(List.of(token));
    }

    @Test
    void dailyReminderSkipsTokenAlreadySentForLocalDay() {
        PushDeviceToken token = token("ExpoPushToken[reminder]");
        token.setUserId(42L);
        token.setTimezone("Asia/Kolkata");
        token.setLastDailyReminderSentDate(LocalDate.parse("2026-07-07"));
        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(token));

        int sent = service.sendDailyUploadReminders();

        assertEquals(0, sent);
        assertTrue(sender.messages.isEmpty());
        verify(submissionRepository, never()).existsIncompleteDailyChallengeForUser(any(), any());
        verify(tokenRepository, never()).saveAll(any());
    }

    private PushDeviceToken token(String value) {
        PushDeviceToken token = new PushDeviceToken();
        token.setUserId(42L);
        token.setExpoPushToken(value);
        token.setPlatform("ios");
        token.setTimezone("UTC");
        token.setEnabled(true);
        token.setLastSeenAt(Timestamp.from(Instant.parse("2026-07-07T13:30:00Z")));
        return token;
    }

    private static final class CapturingSender implements PushNotificationSender {
        private final List<PushMessage> messages = new ArrayList<>();

        @Override
        public void send(List<PushMessage> messages) {
            this.messages.addAll(messages);
        }
    }
}
