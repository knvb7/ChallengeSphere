package com.ironside.challengesphere.service.impl;

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
import com.ironside.challengesphere.service.PushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceImplTest {

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private ChallengeRepository challengeRepository;
    private FollowRepository followRepository;
    private UserBlockRepository userBlockRepository;
    private UserPreferencesRepository preferencesRepository;
    private PushNotificationService pushNotificationService;
    private StringRedisTemplate redisTemplate;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        challengeRepository = mock(ChallengeRepository.class);
        followRepository = mock(FollowRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        preferencesRepository = mock(UserPreferencesRepository.class);
        pushNotificationService = mock(PushNotificationService.class);
        redisTemplate = mock(StringRedisTemplate.class);

        service = new NotificationServiceImpl(
                notificationRepository,
                userRepository,
                challengeRepository,
                followRepository,
                userBlockRepository,
                preferencesRepository,
                pushNotificationService
        );
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);

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
    void notifySubmissionSavesInboxRowsWithoutAnImmediatePush() {
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

        verify(notificationRepository).saveAll(any());
        verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());
    }

    @Test
    void participantRemovalNotificationIncludesReasonAndPushesAfterCommit() {
        User recipient = user(7L, "removed");
        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setTitle("Daily challenge");
        when(userRepository.findById(7L)).thenReturn(Optional.of(recipient));
        when(preferencesRepository.findByUserId(7L)).thenReturn(Optional.empty());

        TransactionSynchronizationManager.initSynchronization();
        try {
            Notification result = service.notifyParticipantRemoved(challenge, 7L, 2L, "x");

            assertNotNull(result);
            assertEquals("CHALLENGE_REMOVAL", result.getType());
            assertEquals("You were removed from \"Daily challenge\". Reason: x", result.getMessage());
            assertTrue(result.getActionObject().contains("\"removalReason\":\"x\""));
            verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(pushNotificationService).sendInteractionPush(
                    7L,
                    "CHALLENGE_REMOVAL",
                    "You were removed from \"Daily challenge\". Reason: x",
                    result.getActionObject());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void challengeInvitesReachFollowersAndFollowingWhileFilteringIneligibleUsers() {
        User actor = user(7L, "alex");
        User following = user(8L, "following");
        User follower = user(9L, "follower");
        User blocked = user(11L, "blocked");
        User inactive = user(12L, "inactive");
        inactive.setActive(false);
        User optedOut = user(13L, "optedout");

        UserPreferences optedOutPreferences = new UserPreferences();
        optedOutPreferences.setUserId(13L);
        optedOutPreferences.setAllowChallengeInvites(false);

        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setTitle("Morning walk");
        challenge.setCreatorId(2L);
        challenge.setIsPrivate(true);

        when(userRepository.findById(7L)).thenReturn(Optional.of(actor));
        when(followRepository.findActiveFollowsByFollowerId(7L)).thenReturn(List.of(
                follow(7L, 8L),
                follow(7L, 11L),
                follow(7L, 12L)
        ));
        when(followRepository.findActiveFollowsByFollowingId(7L)).thenReturn(List.of(
                follow(9L, 7L),
                follow(13L, 7L)
        ));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                following,
                follower,
                blocked,
                inactive,
                optedOut
        ));
        when(preferencesRepository.findByUserIdIn(any())).thenReturn(List.of(optedOutPreferences));
        when(userBlockRepository.existsActiveBlockBetween(11L, 7L)).thenReturn(true);
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );

        service.notifyChallengeInvitesAsync(
                challenge,
                7L,
                List.of(7L, 8L, 9L, 10L, 11L, 12L, 13L)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Notification>> savedNotifications = ArgumentCaptor.forClass(Iterable.class);
        verify(notificationRepository).saveAll(savedNotifications.capture());
        List<Notification> saved = StreamSupport.stream(
                savedNotifications.getValue().spliterator(),
                false
        ).toList();
        assertEquals(Set.of(8L, 9L, 13L), saved.stream().map(Notification::getUserId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(saved.stream().allMatch(notification -> "CHALLENGE_INVITE".equals(notification.getType())));
        assertTrue(saved.stream().allMatch(notification -> notification.getActionObject().contains("\"actorUserId\":7")));
    }

    @Test
    void challengeInvitePersistenceFailureReleasesRedisQuotaAndDedupeReservation() {
        User actor = user(7L, "alex");
        User recipient = user(8L, "friend");
        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setTitle("Morning walk");
        challenge.setCreatorId(7L);
        challenge.setIsPrivate(true);

        when(userRepository.findById(7L)).thenReturn(Optional.of(actor));
        when(followRepository.findActiveFollowsByFollowerId(7L))
                .thenReturn(List.of(follow(7L, 8L)));
        when(followRepository.findActiveFollowsByFollowingId(7L)).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of(recipient));
        when(preferencesRepository.findByUserIdIn(any())).thenReturn(List.of());
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );
        when(notificationRepository.saveAll(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.notifyChallengeInvitesAsync(challenge, 7L, List.of(8L));

        verify(redisTemplate, times(2)).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );
        verify(pushNotificationService, never())
                .sendInteractionPush(any(), any(), any(), any());
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
    void notifyFollowPersistsInboxIndependentlyOfPushDeliveryQuota() {
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));

        Notification result = service.notifyFollow(1L, 2L);

        assertNotNull(result);
        assertEquals("FOLLOW", result.getType());
        verify(notificationRepository).save(any(Notification.class));
        verify(pushNotificationService).sendInteractionPush(
                eq(1L),
                eq("FOLLOW"),
                eq("@actor started following you"),
                any(String.class)
        );
    }

    @Test
    void socialNotificationEntryPointsRunOnAsyncExecutor() throws Exception {
        Async followAsync = NotificationServiceImpl.class
                .getMethod("notifyFollowAsync", Long.class, Long.class)
                .getAnnotation(Async.class);
        Async joinAsync = NotificationServiceImpl.class
                .getMethod("notifyChallengeJoinedAsync", Challenge.class, Long.class, Collection.class)
                .getAnnotation(Async.class);
        Async gloryAsync = NotificationServiceImpl.class
                .getMethod("notifyGloryAsync", Submission.class, Long.class)
                .getAnnotation(Async.class);

        assertNotNull(followAsync);
        assertNotNull(joinAsync);
        assertNotNull(gloryAsync);
        assertEquals("asyncExecutor", followAsync.value());
        assertEquals("asyncExecutor", joinAsync.value());
        assertEquals("asyncExecutor", gloryAsync.value());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notifyGlorySendsPushAfterCommitForPublicAndPrivateSubmissions(boolean privateSubmission) {
        Submission submission = submissionOwnedBy(1L, privateSubmission);
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = pushPreferences(1L);
        preferences.setNotifyOnGlory(true);

        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setTitle(privateSubmission ? "Private crew" : "Public sprint");
        challenge.setIsPrivate(privateSubmission);

        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));
        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge));

        TransactionSynchronizationManager.initSynchronization();
        try {
            Notification result = service.notifyGlory(submission, 2L);

            assertNotNull(result);
            assertEquals("GLORY", result.getType());
            assertEquals("@actor gloried your submission", result.getMessage());
            verify(notificationRepository).save(any(Notification.class));
            verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(pushNotificationService).sendInteractionPush(
                    eq(1L),
                    eq("GLORY"),
                    eq("@actor gloried your submission"),
                    any(String.class)
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notifyCommentSendsPushAfterCommitForPublicAndPrivateSubmissions(boolean privateSubmission) {
        Submission submission = submissionOwnedBy(1L, privateSubmission);
        User recipient = user(1L, "recipient");
        User actor = user(2L, "actor");
        UserPreferences preferences = pushPreferences(1L);
        preferences.setNotifyOnComment(true);

        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setTitle(privateSubmission ? "Private crew" : "Public sprint");
        challenge.setIsPrivate(privateSubmission);

        when(userRepository.findById(1L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(2L)).thenReturn(Optional.of(actor));
        when(preferencesRepository.findByUserId(1L)).thenReturn(Optional.of(preferences));
        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge));

        TransactionSynchronizationManager.initSynchronization();
        try {
            Notification result = service.notifyComment(submission, 2L, 99L);

            assertNotNull(result);
            assertEquals("COMMENT", result.getType());
            assertEquals("@actor commented on your submission", result.getMessage());
            assertTrue(result.getActionObject().contains("\"commentId\":99"));
            verify(notificationRepository).save(any(Notification.class));
            verify(pushNotificationService, never()).sendInteractionPush(any(), any(), any(), any());

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(pushNotificationService).sendInteractionPush(
                    eq(1L),
                    eq("COMMENT"),
                    eq("@actor commented on your submission"),
                    any(String.class)
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
        user.setActive(true);
        return user;
    }

    private Submission submissionOwnedBy(Long userId, boolean privateSubmission) {
        Submission submission = new Submission();
        submission.setId(9L);
        submission.setUserId(userId);
        submission.setChallengeId(3L);
        submission.setDayNumber(1);
        submission.setIsPrivate(privateSubmission);
        return submission;
    }

    private UserPreferences pushPreferences(Long userId) {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(userId);
        preferences.setPushNotificationsEnabled(true);
        return preferences;
    }

    private Follow follow(Long followerId, Long followingId) {
        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFollowingId(followingId);
        follow.setActive(true);
        return follow;
    }
}
