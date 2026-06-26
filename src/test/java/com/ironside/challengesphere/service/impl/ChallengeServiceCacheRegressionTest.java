package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeServiceCacheRegressionTest {

    @Test
    void getUserChallengesReturnsCachedResponseWhenPresent() throws Exception {
        ChallengeServiceImpl service = serviceWithMocks();
        RedisCacheServiceImpl cacheService = mock(RedisCacheServiceImpl.class);
        ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
        UserChallengeRepository userChallengeRepository = mock(UserChallengeRepository.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);

        GetUserChallengesRequest request = new GetUserChallengesRequest();
        request.setUserId(10L);
        request.setCurrentUserId(10L);
        request.setPage(1);
        request.setPageSize(10);

        UserChallengesResponse cached = UserChallengesResponse.create(10L, List.of(challenge(100L, 10L)), 1, 10, 1);
        when(cacheService.getCachedUserChallenges(10L, 1, 10, null, UserChallengesResponse.class))
                .thenReturn(Optional.of(cached));

        UserChallengesResponse response = service.getUserChallenges(request);

        assertSame(cached, response);
        verify(challengeRepository, never()).findByCreatorId(10L);
        verify(userChallengeRepository, never()).findActiveByUserId(10L);
    }

    @Test
    void createChallengeRejectsWhenCreatorAlreadyCreatedFiveChallengesToday() {
        ChallengeServiceImpl service = serviceWithMocks();
        RedisCacheServiceImpl redisCacheService = mock(RedisCacheServiceImpl.class);
        UserRepository userRepository = mock(UserRepository.class);
        ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
        ReflectionTestUtils.setField(service, "redisCacheServiceImpl", redisCacheService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);

        ChallengeCreateRequest request = new ChallengeCreateRequest();
        request.setCreatorUserId(10L);
        request.setTitle("Daily Steps");
        request.setDescription("Walk every day");
        request.setNumberOfDays(7);
        request.setStartDate(LocalDate.of(2026, 6, 3));

        when(redisCacheService.getCachedUserProfile(10L, null, null, User.class)).thenReturn(Optional.of(new User()));
        when(challengeRepository.countCreatedByCreatorIdBetween(eq(10L), any(), any())).thenReturn(5L);

        assertThrows(ChallengeSphereException.class, () -> service.createChallenge(request));
        verify(challengeRepository, never()).save(any(Challenge.class));
    }

    @Test
    void createChallengePrependsSavedChallengeToCreatorUserChallengesCache() throws Exception {
        ChallengeServiceImpl service = serviceWithMocks();
        RedisCacheServiceImpl cacheService = mock(RedisCacheServiceImpl.class);
        RedisCacheServiceImpl redisCacheService = mock(RedisCacheServiceImpl.class);
        UserRepository userRepository = mock(UserRepository.class);
        ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
        UserChallengeRepository userChallengeRepository = mock(UserChallengeRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "redisCacheServiceImpl", redisCacheService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);

        ChallengeCreateRequest request = new ChallengeCreateRequest();
        request.setCreatorUserId(10L);
        request.setTitle("Daily Steps");
        request.setDescription("Walk every day");
        request.setNumberOfDays(7);
        request.setStartDate(LocalDate.of(2026, 6, 3));

        when(redisCacheService.getCachedUserProfile(10L, null, null, User.class)).thenReturn(Optional.of(new User()));
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> {
            Challenge saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        service.createChallenge(request);

        verify(cacheService).cacheChallenge(eq(100L), any(Challenge.class));
        verify(cacheService).prependCreatedChallengeToUserChallenges(eq(10L), eq(10), any(Challenge.class));
        verify(cacheService, never()).invalidateUserChallenges(10L);
        verify(cacheService, never()).invalidateAllFeeds();
    }

    @Test
    void updateChallengeSendsInviteNotificationsForRequestedUsers() throws Exception {
        ChallengeServiceImpl service = serviceWithMocks();
        ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
        RedisCacheServiceImpl cacheService = mock(RedisCacheServiceImpl.class);
        NotificationService notificationService = mock(NotificationService.class);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);

        Challenge existing = challenge(100L, 10L);
        ChallengeUpdateRequest request = new ChallengeUpdateRequest();
        request.setTitle("Updated Daily Steps");
        request.setInvitedUserIds(List.of(20L, 30L));

        when(challengeRepository.findById(100L)).thenReturn(Optional.of(existing));
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Challenge updated = service.updateChallenge(100L, request, 10L, false);

        assertEquals("Updated Daily Steps", updated.getTitle());
        verify(notificationService).notifyChallengeInvitesAsync(existing, 10L, List.of(20L, 30L));
    }

    private ChallengeServiceImpl serviceWithMocks() {
        ChallengeServiceImpl service = new ChallengeServiceImpl();
        ReflectionTestUtils.setField(service, "followRepository", mock(FollowRepository.class));
        ReflectionTestUtils.setField(service, "submissionRepository", mock(SubmissionRepository.class));
        ReflectionTestUtils.setField(service, "mediaUrlService", mock(MediaUrlService.class));
        return service;
    }

    private Challenge challenge(Long id, Long creatorId) {
        Challenge challenge = new Challenge();
        challenge.setId(id);
        challenge.setCreatorId(creatorId);
        challenge.setCurrentOwnerId(creatorId);
        challenge.setTitle("Challenge " + id);
        challenge.setDescription("Description");
        challenge.setActive(true);
        challenge.setIsPrivate(false);
        challenge.setStartDate(LocalDate.of(2026, 6, 3));
        challenge.setNumberOfDays(7);
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        return challenge;
    }
}
