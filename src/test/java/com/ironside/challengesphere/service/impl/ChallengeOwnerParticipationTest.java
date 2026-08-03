package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeOwnerParticipationTest {

    @Test
    void currentOwnerCannotLeaveTheirOwnActiveChallenge() throws Exception {
        ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
        UserChallengeRepository userChallengeRepository = mock(UserChallengeRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RedisCacheServiceImpl cacheService = mock(RedisCacheServiceImpl.class);

        ChallengeServiceImpl service = new ChallengeServiceImpl();
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);

        Challenge challenge = new Challenge();
        challenge.setId(51L);
        challenge.setTitle("Daily challenge");
        challenge.setCreatorId(2L);
        challenge.setCurrentOwnerId(2L);
        challenge.setActive(true);

        UserChallenge ownerParticipation = new UserChallenge();
        ownerParticipation.setUserId(2L);
        ownerParticipation.setChallengeId(51L);
        ownerParticipation.setActive(true);

        LeaveChallengeRequest request = new LeaveChallengeRequest();
        request.setChallengeId(51L);
        request.setUserIds(List.of(2L));

        when(challengeRepository.findById(51L)).thenReturn(Optional.of(challenge));
        when(userChallengeRepository.findByChallengeId(51L)).thenReturn(List.of(ownerParticipation));

        LeaveChallengeResponse response = service.leaveChallenge(request);

        assertFalse(response.isSuccess());
        assertTrue(ownerParticipation.getActive());
        verify(userChallengeRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
