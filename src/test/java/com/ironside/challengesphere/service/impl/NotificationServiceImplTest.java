package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.PushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceImplTest {

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private UserPreferencesRepository preferencesRepository;
    private PushNotificationService pushNotificationService;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        preferencesRepository = mock(UserPreferencesRepository.class);
        pushNotificationService = mock(PushNotificationService.class);

        service = new NotificationServiceImpl(
                notificationRepository,
                userRepository,
                mock(ChallengeRepository.class),
                mock(FollowRepository.class),
                userBlockRepository,
                preferencesRepository,
                pushNotificationService
        );

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void notifyFollowSendsPushOnlyAfterTransactionCommit() {
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.empty());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyFollow(1L, 2L);

            verify(notificationRepository).save(any(Notification.class));
            verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(pushNotificationService).sendInteractionPush(
                    eq(1L),
                    eq("FOLLOW"),
                    eq("@actor started following you"),
                    any(String.class)
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void notifySubmissionSavesInboxRowsBeforeDispatchingPushes() {
        Submission submission = new Submission();
        submission.setId(9L);
        submission.setUserId(2L);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);
        submission.setIsPrivate(false);

        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setTitle("July sprint");
        challenge.setIsPrivate(false);

        User author = user(2L, "actor");
        User recipient = user(1L, "recipient");
        when(userRepository.findById(2L)).thenReturn(Optional.of(author));
        when(userRepository.findAllById(any())).thenReturn(List.of(recipient));
        when(preferencesRepository.findByUserIdIn(any())).thenReturn(List.of());

        service.notifySubmission(submission, challenge, List.of(1L));

        InOrder order = inOrder(notificationRepository, pushNotificationService);
        order.verify(notificationRepository).saveAll(any());
        order.verify(pushNotificationService).sendInteractionPush(
                eq(1L),
                eq("SUBMISSION"),
                eq("@actor posted Day 1 in July sprint"),
                any(String.class)
        );
    }

    @Test
    void notifyFollowSkipsSelfNotification() {
        Notification result = service.notifyFollow(1L, 1L);

        assertNull(result);
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyFollowSkipsBlockedUsers() {
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(userBlockRepository.existsActiveBlockBetween(1L, 2L)).thenReturn(true);

        Notification result = service.notifyFollow(1L, 2L);

        assertNull(result);
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyGlorySkipsSelfNotification() {
        Submission submission = new Submission();
        submission.setId(9L);
        submission.setUserId(1L);
        submission.setChallengeId(3L);

        Notification result = service.notifyGlory(submission, 1L);

        assertNull(result);
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
    }

    @Test
    void notifyCommentRespectsDisabledPushPreference() {
        Submission submission = new Submission();
        submission.setId(9L);
        submission.setUserId(1L);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);

        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(1L);
        preferences.setPushNotificationsEnabled(false);
        preferences.setNotifyOnComment(true);
        preferences.setEmailNotificationsEnabled(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));

        Notification result = service.notifyComment(submission, 2L, 99L);

        assertNotNull(result);
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
        verify(notificationRepository).save(any(Notification.class));
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNotifications(1);
        return user;
    }
}
