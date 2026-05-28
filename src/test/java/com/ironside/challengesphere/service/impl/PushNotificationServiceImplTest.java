package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.PushNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceImplTest {

    @Mock
    private PushDeviceTokenRepository tokenRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private PushNotificationSender sender;

    private PushNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.fixed(Instant.parse("2026-05-28T13:30:00Z"), ZoneId.of("UTC"))
        );
    }

    @Test
    void sendsFollowPushToEnabledTokens() {
        PushDeviceToken ios = token(1L, "ExponentPushToken[ios]", "Asia/Kolkata");
        PushDeviceToken android = token(1L, "ExponentPushToken[android]", "America/New_York");

        when(tokenRepository.findEnabledByUserId(1L)).thenReturn(List.of(ios, android));

        service.sendInteractionPush(1L, "FOLLOW", "@alex started following you", "{\"actorUserId\":2}");

        ArgumentCaptor<List<PushNotificationSender.PushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(sender).send(captor.capture());

        List<PushNotificationSender.PushMessage> messages = captor.getValue();
        assertEquals(2, messages.size());
        assertEquals("ExponentPushToken[ios]", messages.get(0).to());
        assertEquals("New follower", messages.get(0).title());
        assertEquals("@alex started following you", messages.get(0).body());
        assertEquals("FOLLOW", messages.get(0).data().get("type"));
    }

    @Test
    void doesNotSendPushForUnsupportedNotificationTypes() {
        service.sendInteractionPush(1L, "CHALLENGE_INVITE", "Join a challenge", "{}");

        verify(tokenRepository, never()).findEnabledByUserId(any());
        verify(sender, never()).send(any());
    }

    @Test
    void sendsReminderOnlyWhenTokenLocalTimeIsSevenPmAndUploadIsMissing() {
        PushDeviceToken due = token(1L, "ExponentPushToken[due]", "Asia/Kolkata");
        PushDeviceToken notSeven = token(2L, "ExponentPushToken[early]", "America/New_York");
        PushDeviceToken alreadySent = token(3L, "ExponentPushToken[sent]", "Asia/Kolkata");
        alreadySent.setLastDailyReminderSentDate(LocalDate.of(2026, 5, 28));

        when(tokenRepository.findEnabledReminderTokens()).thenReturn(List.of(due, notSeven, alreadySent));
        when(submissionRepository.existsIncompleteDailyChallengeForUser(1L, LocalDate.of(2026, 5, 28))).thenReturn(true);

        int sent = service.sendDailyUploadReminders();

        ArgumentCaptor<List<PushNotificationSender.PushMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sender).send(messagesCaptor.capture());
        verify(tokenRepository).saveAll(List.of(due));

        assertEquals(1, sent);
        assertEquals("ExponentPushToken[due]", messagesCaptor.getValue().get(0).to());
        assertEquals("Upload reminder", messagesCaptor.getValue().get(0).title());
        assertEquals(LocalDate.of(2026, 5, 28), due.getLastDailyReminderSentDate());
    }

    private PushDeviceToken token(Long userId, String token, String timezone) {
        PushDeviceToken deviceToken = new PushDeviceToken();
        deviceToken.setUserId(userId);
        deviceToken.setExpoPushToken(token);
        deviceToken.setTimezone(timezone);
        deviceToken.setEnabled(true);
        return deviceToken;
    }
}
