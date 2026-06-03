package com.ironside.challengesphere.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationPushFanoutRegressionTest {

    @Test
    void inAppSubmissionInviteAndChallengeUpdateNotificationsAlsoFanOutToPush() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/ironside/challengesphere/service/impl/NotificationServiceImpl.java"
        ));

        assertTrue(
                source.contains("pushNotificationService.sendInteractionPush(recipient.getId(), TYPE_SUBMISSION")
                        && source.contains("pushNotificationService.sendInteractionPush(recipient.getId(), TYPE_CHALLENGE_INVITE")
                        && source.contains("pushNotificationService.sendInteractionPush(recipient.getId(), TYPE_CHALLENGE_UPDATE"),
                "User-visible in-app notification fan-outs must also call the push sender"
        );
    }
}
