package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeInAppInviteTest {
    private ChallengeServiceImpl service;
    private ChallengeRepository challengeRepository;
    private UserChallengeRepository userChallengeRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        service = new ChallengeServiceImpl();
        challengeRepository = mock(ChallengeRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        notificationService = mock(NotificationService.class);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
    }

    @Test
    void activeParticipantCanInviteFriendsWithoutChallengeEditAuthority() throws Exception {
        Challenge challenge = challenge();
        UserChallenge actorMembership = membership(7L, true, false);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.of(actorMembership));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of(actorMembership));

        service.inviteChallengeUsers(51L, 7L, List.of(8L, 9L, 8L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> recipients = ArgumentCaptor.forClass(Collection.class);
        verify(notificationService).notifyChallengeInvitesAsync(eq(challenge), eq(7L), recipients.capture());
        assertEquals(Set.of(8L, 9L), Set.copyOf(recipients.getValue()));
        verify(challengeRepository, never()).save(any(Challenge.class));
    }

    @Test
    void inAppInviteNotificationWaitsForOuterTransactionCommit() throws Exception {
        Challenge challenge = challenge();
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.inviteChallengeUsers(51L, 2L, List.of(8L));

            verify(notificationService, never()).notifyChallengeInvitesAsync(any(), any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(notificationService).notifyChallengeInvitesAsync(challenge, 2L, List.of(8L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void participantInviteAccessDoesNotGrantChallengeEditAccess() {
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge()));
        ChallengeUpdateRequest edit = new ChallengeUpdateRequest();
        edit.setTitle("Participant edit attempt");

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.updateChallenge(51L, edit, 7L, false)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(challengeRepository, never()).save(any(Challenge.class));
    }

    @Test
    void nonParticipantCannotSendInAppInvites() {
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge()));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.empty());

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.inviteChallengeUsers(51L, 7L, List.of(8L))
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(notificationService, never()).notifyChallengeInvitesAsync(any(), any(), any());
    }

    @Test
    void bannedParticipantCannotSendInAppInvites() {
        UserChallenge bannedActor = membership(7L, true, true);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge()));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.of(bannedActor));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.inviteChallengeUsers(51L, 7L, List.of(8L))
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(notificationService, never()).notifyChallengeInvitesAsync(any(), any(), any());
    }

    @Test
    void participantInviteCannotReinstateAUserRemovedByTheCreator() {
        UserChallenge actorMembership = membership(7L, true, false);
        UserChallenge removedMembership = membership(8L, false, true);
        removedMembership.setRemovalReason("Repeated spam posts");
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge()));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.of(actorMembership));
        when(userChallengeRepository.findByChallengeId(51L))
                .thenReturn(List.of(actorMembership, removedMembership));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.inviteChallengeUsers(51L, 7L, List.of(8L))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(userChallengeRepository, never()).saveAll(any());
        verify(notificationService, never()).notifyChallengeInvitesAsync(any(), any(), any());
    }

    @Test
    void originalCreatorInviteCanReinstateAUserRemovedEarlier() throws Exception {
        Challenge challenge = challenge();
        UserChallenge removedMembership = membership(8L, false, true);
        removedMembership.setRemovalReason("Repeated spam posts");
        removedMembership.setRemovedAt(new Timestamp(System.currentTimeMillis()));
        removedMembership.setRemovedByUserId(2L);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of(removedMembership));

        service.inviteChallengeUsers(51L, 2L, List.of(8L));

        assertFalse(removedMembership.getBanned());
        assertNull(removedMembership.getRemovalReason());
        assertNull(removedMembership.getRemovedAt());
        assertNull(removedMembership.getRemovedByUserId());
        verify(userChallengeRepository).saveAll(List.of(removedMembership));
        verify(notificationService).notifyChallengeInvitesAsync(challenge, 2L, List.of(8L));
    }

    @Test
    void completedChallengeRejectsNewInAppInvites() {
        Challenge challenge = challenge();
        challenge.setStartDate(LocalDate.now().minusDays(10));
        challenge.setNumberOfDays(2);
        UserChallenge actorMembership = membership(7L, true, false);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.of(actorMembership));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.inviteChallengeUsers(51L, 7L, List.of(8L))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(notificationService, never()).notifyChallengeInvitesAsync(any(), any(), any());
    }

    private Challenge challenge() {
        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setTitle("Daily challenge");
        challenge.setCreatorId(2L);
        challenge.setCurrentOwnerId(2L);
        challenge.setActive(true);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(7);
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        return challenge;
    }

    private UserChallenge membership(Long userId, boolean active, boolean banned) {
        UserChallenge membership = new UserChallenge();
        membership.setUserId(userId);
        membership.setChallengeId(51L);
        membership.setActive(active);
        membership.setBanned(banned);
        return membership;
    }
}
