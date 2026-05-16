package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Follow;
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
import com.ironside.challengesphere.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private UserPreferencesRepository preferencesRepository;

    @Mock
    private EmailService emailService;

    private ObjectMapper objectMapper;
    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        notificationService = new NotificationServiceImpl(
                notificationRepository,
                userRepository,
                challengeRepository,
                followRepository,
                userBlockRepository,
                preferencesRepository,
                emailService,
                objectMapper
        );
    }

    @Test
    void notifyFollowDoesNotCreateNotificationWhenFollowPreferenceIsDisabled() {
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences prefs = prefs(1L);
        prefs.setNotifyOnFollow(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(prefs));

        Notification result = notificationService.notifyFollow(1L, 2L);

        assertNull(result);
        verify(notificationRepository, never()).save(any(Notification.class));
        verify(emailService, never()).sendPlainEmail(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifySubmissionFanoutSkipsAuthorAndRecipientsWhoDisabledChallengeUpdates() throws Exception {
        User author = user(1L, "author");
        User allowedRecipient = user(2L, "allowed");
        User disabledRecipient = user(3L, "disabled");
        UserPreferences allowedPrefs = prefs(2L);
        UserPreferences disabledPrefs = prefs(3L);
        disabledPrefs.setNotifyOnChallengeUpdate(false);

        Challenge challenge = new Challenge();
        challenge.setId(10L);
        challenge.setTitle("Morning Run");
        challenge.setIsPrivate(false);

        Submission submission = new Submission();
        submission.setId(99L);
        submission.setUserId(1L);
        submission.setChallengeId(10L);
        submission.setDayNumber(4);
        submission.setMediaUrls("https://cdn.example.com/submission.jpg");
        submission.setCreatedAt(Timestamp.from(Instant.now()));
        submission.setIsPrivate(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(author));
        when(preferencesRepository.findByUserIdIn(Set.of(2L, 3L)))
                .thenReturn(List.of(allowedPrefs, disabledPrefs));
        when(userRepository.findAllById(Set.of(2L, 3L)))
                .thenReturn(List.of(allowedRecipient, disabledRecipient));
        when(notificationRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    Collection<Notification> notifications = new ArrayList<>();
                    invocation.<Iterable<Notification>>getArgument(0).forEach(notifications::add);
                    return notifications;
                });

        int created = notificationService.notifySubmission(submission, challenge, List.of(1L, 2L, 3L));

        ArgumentCaptor<Iterable<Notification>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(notificationRepository).saveAll(captor.capture());

        List<Notification> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);

        assertEquals(1, created);
        assertEquals(1, saved.size());
        assertEquals(2L, saved.get(0).getUserId());
        assertEquals("SUBMISSION", saved.get(0).getType());
        assertFalse(saved.get(0).isRead());

        JsonNode action = objectMapper.readTree(saved.get(0).getActionObject());
        assertEquals(99L, action.get("submissionId").asLong());
        assertEquals(10L, action.get("challengeId").asLong());
        assertEquals("Morning Run", action.get("challengeTitle").asText());
        assertNotNull(action.get("mediaUrl"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyChallengeInvitesSendsToRequestedUsersWithInvitePreference() throws Exception {
        User actor = user(1L, "creator");
        User follower = user(2L, "follower");
        User following = user(3L, "following");
        User stranger = user(4L, "stranger");
        User disabled = user(5L, "disabled");
        UserPreferences followerPrefs = prefs(2L);
        UserPreferences followingPrefs = prefs(3L);
        UserPreferences strangerPrefs = prefs(4L);
        UserPreferences disabledPrefs = prefs(5L);
        disabledPrefs.setAllowChallengeInvites(false);

        Challenge challenge = new Challenge();
        challenge.setId(10L);
        challenge.setTitle("Morning Run");
        challenge.setCreatorId(1L);
        challenge.setIsPrivate(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserIdIn(any())).thenReturn(List.of(followerPrefs, followingPrefs, strangerPrefs, disabledPrefs));
        when(userRepository.findAllById(any())).thenReturn(List.of(follower, following, stranger, disabled));
        when(notificationRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    Collection<Notification> notifications = new ArrayList<>();
                    invocation.<Iterable<Notification>>getArgument(0).forEach(notifications::add);
                    return notifications;
                });

        notificationService.notifyChallengeInvitesAsync(challenge, 1L, List.of(2L, 3L, 4L, 5L));

        ArgumentCaptor<Iterable<Notification>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(notificationRepository).saveAll(captor.capture());

        List<Notification> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);

        assertEquals(3, saved.size());
        assertEquals(Set.of(2L, 3L, 4L), saved.stream().map(Notification::getUserId).collect(java.util.stream.Collectors.toSet()));
        assertEquals("CHALLENGE_INVITE", saved.get(0).getType());

        JsonNode action = objectMapper.readTree(saved.get(0).getActionObject());
        assertEquals("CHALLENGE_INVITE", action.get("actionType").asText());
        assertEquals("PENDING", action.get("status").asText());
        assertEquals(10L, action.get("challengeId").asLong());
        assertEquals("Morning Run", action.get("challengeTitle").asText());
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNotifications(1);
        return user;
    }

    private Follow follow(Long followerId, Long followingId) {
        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFollowingId(followingId);
        follow.setActive(true);
        return follow;
    }

    private UserPreferences prefs(Long userId) {
        UserPreferences prefs = new UserPreferences();
        prefs.setUserId(userId);
        prefs.setPushNotificationsEnabled(true);
        prefs.setEmailNotificationsEnabled(false);
        prefs.setNotifyOnFollow(true);
        prefs.setNotifyOnGlory(true);
        prefs.setNotifyOnComment(true);
        prefs.setNotifyOnChallengeInvite(true);
        prefs.setNotifyOnChallengeUpdate(true);
        return prefs;
    }
}
