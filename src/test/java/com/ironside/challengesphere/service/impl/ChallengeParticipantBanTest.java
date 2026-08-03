package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.config.ChallengeParticipationProperties;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeParticipantBanTest {

    private ChallengeServiceImpl service;
    private ChallengeRepository challengeRepository;
    private UserChallengeRepository userChallengeRepository;
    private UserRepository userRepository;
    private RedisCacheServiceImpl cacheService;
    private NotificationService notificationService;
    private ChallengeParticipationProperties challengeParticipationProperties;

    @BeforeEach
    void setUp() {
        service = new ChallengeServiceImpl();
        challengeRepository = mock(ChallengeRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        cacheService = mock(RedisCacheServiceImpl.class);
        notificationService = mock(NotificationService.class);
        challengeParticipationProperties = mock(ChallengeParticipationProperties.class);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "challengeParticipationProperties", challengeParticipationProperties);
    }

    @Test
    void creatorRemovalPersistsTrimmedReasonAndBanMetadata() throws Exception {
        Challenge challenge = challenge();
        UserChallenge membership = membership(true);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.of(membership));

        LeaveChallengeResponse response = service.removeParticipant(
                51L, 2L, 7L, "  Repeated spam posts  ");

        assertTrue(response.isSuccess());
        assertFalse(membership.getActive());
        assertTrue(membership.getBanned());
        assertEquals("Repeated spam posts", membership.getRemovalReason());
        assertEquals(2L, membership.getRemovedByUserId());
        assertNotNull(membership.getRemovedAt());
        verify(userChallengeRepository).save(membership);
        verify(cacheService).invalidateChallengeParticipants(51L);
        verify(cacheService).invalidateTimeline(7L);
        verify(userRepository).adjustChallengeAcceptedCountForAUser(7L, -1);
    }

    @Test
    void creatorRemovalSucceedsWhenCacheInvalidationIsUnavailable() throws Exception {
        Challenge challenge = challenge();
        UserChallenge membership = membership(true);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByUserIdAndChallengeId(7L, 51L))
                .thenReturn(Optional.of(membership));
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(cacheService).invalidateTimeline(7L);

        LeaveChallengeResponse response = service.removeParticipant(
                51L, 2L, 7L, "Repeated spam posts");

        assertTrue(response.isSuccess());
        assertFalse(membership.getActive());
        assertTrue(membership.getBanned());
        verify(userChallengeRepository).save(membership);
        verify(userRepository).adjustChallengeAcceptedCountForAUser(7L, -1);
    }

    @Test
    void removalRejectsReasonShorterThanTenTrimmedCharacters() {
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge()));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.removeParticipant(51L, 2L, 7L, " too short "));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(
                "A removal reason must contain between 10 and 500 characters",
                error.getResponseMessage());
        verify(userChallengeRepository, never()).save(any(UserChallenge.class));
    }

    @Test
    void bannedParticipantCannotJoinNormallyOrFromVerifiedInvite() {
        Challenge challenge = challenge();
        UserChallenge membership = membership(false);
        membership.setBanned(true);
        User user = new User();
        user.setId(7L);
        JoinChallengeRequest request = new JoinChallengeRequest();
        request.setChallengeId(51L);
        request.setUserIds(List.of(7L));

        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userRepository.findAllById(List.of(7L))).thenReturn(List.of(user));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of(membership));

        ChallengeSphereException normalError = assertThrows(
                ChallengeSphereException.class,
                () -> service.joinChallenge(request));
        ChallengeSphereException inviteError = assertThrows(
                ChallengeSphereException.class,
                () -> service.joinChallengeFromVerifiedInvite(request));

        assertEquals(HttpStatus.FORBIDDEN, normalError.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, inviteError.getStatus());
        assertEquals(
                "You were removed from this challenge by its creator.",
                inviteError.getResponseMessage());
        verify(userChallengeRepository, never()).saveAll(any());
    }

    @Test
    void voluntaryLeaveDoesNotSetBanned() throws Exception {
        Challenge challenge = challenge();
        UserChallenge membership = membership(true);
        membership.setBanned(false);
        LeaveChallengeRequest request = new LeaveChallengeRequest();
        request.setChallengeId(51L);
        request.setUserIds(List.of(7L));
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of(membership));

        LeaveChallengeResponse response = service.leaveChallenge(request);

        assertTrue(response.isSuccess());
        assertFalse(membership.getActive());
        assertFalse(membership.getBanned());
        assertNull(membership.getRemovalReason());
    }

    @Test
    void targetedCreatorInviteClearsBanMetadataAndPermitsVerifiedRejoin() throws Exception {
        Challenge challenge = challenge();
        UserChallenge membership = membership(false);
        membership.setBanned(true);
        membership.setRemovalReason("Repeated spam posts");
        membership.setRemovedAt(new Timestamp(System.currentTimeMillis()));
        membership.setRemovedByUserId(2L);
        ChallengeUpdateRequest request = new ChallengeUpdateRequest();
        request.setInvitedUserIds(List.of(7L));
        User user = new User();
        user.setId(7L);

        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(challengeRepository.save(challenge)).thenReturn(challenge);
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of(membership));
        when(userRepository.findAllById(List.of(7L))).thenReturn(List.of(user));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(userChallengeRepository.countSlotConsumingParticipations(7L)).thenReturn(0L);
        when(challengeParticipationProperties.getMaxActiveParticipations()).thenReturn(0);

        service.updateChallenge(51L, request, 2L, false);

        assertFalse(membership.getBanned());
        assertNull(membership.getRemovalReason());
        assertNull(membership.getRemovedAt());
        assertNull(membership.getRemovedByUserId());
        verify(notificationService).notifyChallengeInvitesAsync(challenge, 2L, List.of(7L));

        JoinChallengeRequest joinRequest = new JoinChallengeRequest();
        joinRequest.setChallengeId(51L);
        joinRequest.setUserIds(List.of(7L));
        JoinChallengeResponse response = service.joinChallengeFromVerifiedInvite(joinRequest);

        assertTrue(response.isSuccess());
        assertTrue(membership.getActive());
        verify(userChallengeRepository, times(2)).saveAll(List.of(membership));
    }

    @Test
    void challengeJoinNotificationWaitsForOuterTransactionCommit() throws Exception {
        Challenge challenge = challenge();
        User joiner = new User();
        joiner.setId(7L);
        JoinChallengeRequest request = new JoinChallengeRequest();
        request.setChallengeId(51L);
        request.setUserIds(List.of(7L));

        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userRepository.findAllById(List.of(7L))).thenReturn(List.of(joiner));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(joiner));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of());
        when(userChallengeRepository.countSlotConsumingParticipations(7L)).thenReturn(0L);
        when(challengeParticipationProperties.getMaxActiveParticipations()).thenReturn(0);

        TransactionSynchronizationManager.initSynchronization();
        try {
            JoinChallengeResponse response = service.joinChallenge(request);

            assertTrue(response.isSuccess());
            verify(notificationService, never()).notifyChallengeJoinedAsync(any(), any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(notificationService).notifyChallengeJoinedAsync(challenge, 7L, Set.of(2L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Challenge challenge() {
        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setTitle("Daily challenge");
        challenge.setCreatorId(2L);
        challenge.setCurrentOwnerId(2L);
        challenge.setActive(true);
        challenge.setIsPrivate(false);
        return challenge;
    }

    private UserChallenge membership(boolean active) {
        UserChallenge membership = new UserChallenge();
        membership.setId(99L);
        membership.setUserId(7L);
        membership.setChallengeId(51L);
        membership.setActive(active);
        membership.setBanned(false);
        return membership;
    }
}
