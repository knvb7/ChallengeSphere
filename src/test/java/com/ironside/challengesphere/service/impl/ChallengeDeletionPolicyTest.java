package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeDeletionPolicyTest {

    private ChallengeServiceImpl service;
    private ChallengeRepository challengeRepository;
    private UserChallengeRepository userChallengeRepository;

    @BeforeEach
    void setUp() {
        service = new ChallengeServiceImpl();
        challengeRepository = mock(ChallengeRepository.class);
        userChallengeRepository = mock(UserChallengeRepository.class);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
    }

    @Test
    void allowsThreeMembersAtExactlyThreeDays() {
        long now = System.currentTimeMillis();
        Challenge challenge = challengeCreatedAt(now - Duration.ofDays(3).toMillis());

        assertTrue(ChallengeServiceImpl.isChallengeDeletable(challenge, activeMembers(3), now));
    }

    @Test
    void blocksMoreThanThreeMembers() {
        long now = System.currentTimeMillis();
        Challenge challenge = challengeCreatedAt(now);

        assertFalse(ChallengeServiceImpl.isChallengeDeletable(challenge, activeMembers(4), now));
    }

    @Test
    void blocksMoreThanThreeDays() {
        long now = System.currentTimeMillis();
        Challenge challenge = challengeCreatedAt(now - Duration.ofDays(3).toMillis() - 1);

        assertFalse(ChallengeServiceImpl.isChallengeDeletable(challenge, activeMembers(3), now));
    }

    @Test
    void deleteEndpointUsesThePolicy() {
        long now = System.currentTimeMillis();
        Challenge challenge = challengeCreatedAt(now);
        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByChallengeIds(List.of(51L))).thenReturn(activeMembers(4));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.deleteChallenge(51L, 2L));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(
                "You can delete a challenge only in its first 3 days and while it has 3 members or fewer",
                error.getResponseMessage());
        verify(challengeRepository, never()).save(challenge);
    }

    private Challenge challengeCreatedAt(long createdAtMillis) {
        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setCreatorId(2L);
        challenge.setCurrentOwnerId(2L);
        challenge.setActive(true);
        challenge.setCreatedAt(new Timestamp(createdAtMillis));
        return challenge;
    }

    private List<UserChallenge> activeMembers(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(userId -> {
                    UserChallenge membership = new UserChallenge();
                    membership.setChallengeId(51L);
                    membership.setUserId(userId);
                    membership.setActive(true);
                    return membership;
                })
                .toList();
    }
}
