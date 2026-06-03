package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.PushNotificationSender;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushNotificationServiceImplTest {

    @Test
    void sendsPushForAllUserVisibleNotificationTypes() {
        PushDeviceTokenRepository tokenRepository = mock(PushDeviceTokenRepository.class);
        SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
        PushNotificationSender sender = mock(PushNotificationSender.class);
        PushDeviceToken token = new PushDeviceToken();
        token.setUserId(10L);
        token.setExpoPushToken("ExpoPushToken[test]");
        when(tokenRepository.findEnabledByUserId(10L)).thenReturn(List.of(token));
        PushNotificationServiceImpl service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.systemUTC()
        );

        for (String type : List.of("FOLLOW", "GLORY", "COMMENT", "SUBMISSION", "CHALLENGE_INVITE", "CHALLENGE_UPDATE")) {
            service.sendInteractionPush(10L, type, "message", "{\"challengeId\":1}");
        }

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PushNotificationSender.PushMessage>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sender, org.mockito.Mockito.times(6)).send(captor.capture());
        assertEquals(
                List.of("New follower", "New like", "New comment", "New submission", "Challenge invite", "Challenge update"),
                captor.getAllValues().stream().map(messages -> messages.get(0).title()).toList()
        );
    }

    @Test
    void skipsUnsupportedNotificationTypes() {
        PushDeviceTokenRepository tokenRepository = mock(PushDeviceTokenRepository.class);
        SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
        PushNotificationSender sender = mock(PushNotificationSender.class);
        PushNotificationServiceImpl service = new PushNotificationServiceImpl(
                tokenRepository,
                submissionRepository,
                sender,
                Clock.systemUTC()
        );

        service.sendInteractionPush(10L, "SYSTEM", "message", "{}");

        verify(sender, never()).send(anyList());
    }
}
