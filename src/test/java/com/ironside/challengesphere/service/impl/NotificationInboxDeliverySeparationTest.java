package com.ironside.challengesphere.service.impl;

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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationInboxDeliverySeparationTest {

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private UserPreferencesRepository preferencesRepository;
    private PushNotificationService pushNotificationService;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        preferencesRepository = mock(UserPreferencesRepository.class);
        pushNotificationService = mock(PushNotificationService.class);
        service = new NotificationServiceImpl(
                notificationRepository,
                userRepository,
                mock(ChallengeRepository.class),
                mock(FollowRepository.class),
                mock(UserBlockRepository.class),
                preferencesRepository,
                pushNotificationService
        );
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void commentPersistsInInboxWhenGlobalPhonePushIsDisabled() {
        Submission submission = submissionOwnedBy(1L);
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = preferencesFor(1L);
        preferences.setPushNotificationsEnabled(false);
        preferences.setNotifyOnComment(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));

        Notification result = service.notifyComment(submission, 2L, 99L);

        assertNotNull(result);
        verify(notificationRepository).save(any(Notification.class));
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
    }

    @Test
    void gloryPersistsInInboxWhenGloryPhonePushIsDisabled() {
        Submission submission = submissionOwnedBy(1L);
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = preferencesFor(1L);
        preferences.setPushNotificationsEnabled(true);
        preferences.setNotifyOnGlory(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));

        Notification result = service.notifyGlory(submission, 2L);

        assertNotNull(result);
        verify(notificationRepository).save(any(Notification.class));
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
    }

    private Submission submissionOwnedBy(Long userId) {
        Submission submission = new Submission();
        submission.setId(9L);
        submission.setUserId(userId);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);
        return submission;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setActive(true);
        user.setNotifications(1);
        return user;
    }

    private UserPreferences preferencesFor(Long userId) {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(userId);
        preferences.setEmailNotificationsEnabled(false);
        preferences.setNotifyOnFollow(true);
        preferences.setNotifyOnComment(true);
        preferences.setNotifyOnGlory(true);
        return preferences;
    }
}
