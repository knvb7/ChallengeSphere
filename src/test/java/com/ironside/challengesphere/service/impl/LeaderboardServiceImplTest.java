package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.UpdateLeaderboardScoreRequest;
import com.ironside.challengesphere.api.request.GetLeaderboardRequest;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.LeaderboardEntry;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.LeaderboardEntryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaderboardServiceImplTest {

    private LeaderboardEntryRepository leaderboardEntryRepository;
    private ChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private RedisCacheServiceImpl cacheService;
    private LeaderboardServiceImpl service;

    @BeforeEach
    void setUp() {
        leaderboardEntryRepository = mock(LeaderboardEntryRepository.class);
        challengeRepository = mock(ChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        cacheService = mock(RedisCacheServiceImpl.class);

        service = new LeaderboardServiceImpl();
        ReflectionTestUtils.setField(service, "leaderboardEntryRepository", leaderboardEntryRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", mock(UserChallengeRepository.class));
        ReflectionTestUtils.setField(service, "submissionRepository", mock(SubmissionRepository.class));
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "mediaUrlService", mock(MediaUrlService.class));
    }

    @Test
    void incrementScoreWritesDatabaseRanksAndRedisImmediately() {
        LeaderboardEntry entry = leaderboardEntry(3L, 7L, 20.0, 2, 0);
        when(leaderboardEntryRepository.findByChallengeIdAndUserId(3L, 7L)).thenReturn(Optional.of(entry));
        when(leaderboardEntryRepository.save(entry)).thenReturn(entry);

        service.incrementScore(3L, 7L, 10.0, "New submission");

        assertEquals(30.0, entry.getScore());
        assertEquals(3, entry.getSubmissionCount());
        verify(leaderboardEntryRepository).save(entry);
        verify(leaderboardEntryRepository).updateRanksForChallenge(3L);
        verify(cacheService).addToLeaderboard(3L, 7L, 30.0);
    }

    @Test
    void decrementSubmissionScoreWritesDatabaseRanksAndRedisImmediately() {
        LeaderboardEntry entry = leaderboardEntry(3L, 7L, 30.0, 3, 0);
        when(leaderboardEntryRepository.findByChallengeIdAndUserId(3L, 7L)).thenReturn(Optional.of(entry));
        when(leaderboardEntryRepository.save(entry)).thenReturn(entry);

        service.decrementSubmissionScore(3L, 7L, 10.0, "Deleted submission");

        assertEquals(20.0, entry.getScore());
        assertEquals(2, entry.getSubmissionCount());
        verify(leaderboardEntryRepository).save(entry);
        verify(leaderboardEntryRepository).updateRanksForChallenge(3L);
        verify(cacheService).addToLeaderboard(3L, 7L, 20.0);
    }

    @Test
    void updateScoreWritesDatabaseRanksAndRedisImmediately() throws Exception {
        LeaderboardEntry entry = leaderboardEntry(3L, 7L, 20.0, 2, 0);
        when(leaderboardEntryRepository.findByChallengeIdAndUserId(3L, 7L)).thenReturn(Optional.of(entry));
        when(leaderboardEntryRepository.save(entry)).thenReturn(entry);

        UpdateLeaderboardScoreRequest request = new UpdateLeaderboardScoreRequest();
        request.setChallengeId(3L);
        request.setUserId(7L);
        request.setScore(5.0);
        request.setSetAbsolute(false);

        service.updateScore(request);

        assertEquals(25.0, entry.getScore());
        verify(leaderboardEntryRepository).save(entry);
        verify(leaderboardEntryRepository).updateRanksForChallenge(3L);
        verify(cacheService).addToLeaderboard(3L, 7L, 25.0);
    }

    @Test
    void incrementGloryCountPreservesPointsOnlyScoreAndOrdering() {
        LeaderboardEntry entry = leaderboardEntry(3L, 7L, 20.0, 2, 4);
        when(leaderboardEntryRepository.findByChallengeIdAndUserId(3L, 7L)).thenReturn(Optional.of(entry));
        when(leaderboardEntryRepository.save(entry)).thenReturn(entry);

        service.incrementGloryScore(3L, 7L, 1.0, "Received glory");

        assertEquals(20.0, entry.getScore());
        assertEquals(5, entry.getGloryPoints());
        verify(leaderboardEntryRepository).save(entry);
        verify(leaderboardEntryRepository).updateRanksForChallenge(3L);
        verify(cacheService).addToLeaderboard(3L, 7L, 20.0);
    }

    @Test
    void decrementGloryCountPreservesPointsOnlyScoreAndOrdering() {
        LeaderboardEntry entry = leaderboardEntry(3L, 7L, 20.0, 2, 4);
        when(leaderboardEntryRepository.findByChallengeIdAndUserId(3L, 7L)).thenReturn(Optional.of(entry));
        when(leaderboardEntryRepository.save(entry)).thenReturn(entry);

        service.decrementGloryScore(3L, 7L, 1.0, "Removed glory");

        assertEquals(20.0, entry.getScore());
        assertEquals(3, entry.getGloryPoints());
        verify(leaderboardEntryRepository).save(entry);
        verify(leaderboardEntryRepository).updateRanksForChallenge(3L);
        verify(cacheService).addToLeaderboard(3L, 7L, 20.0);
    }

    @Test
    void leaderboardReadEndpointsPreserveOrderingVisibleTieRanksAndUserPositionSemantics() throws Exception {
        Challenge challenge = new Challenge();
        challenge.setId(3L);
        challenge.setTitle("Keep moving");
        challenge.setActive(true);
        challenge.setIsPrivate(false);

        LeaderboardEntry first = leaderboardEntry(3L, 11L, 100.0, 10, 2);
        first.getUser().setUsername("first_user");
        LeaderboardEntry tiedFirst = leaderboardEntry(3L, 12L, 80.0, 8, 20);
        tiedFirst.getUser().setUsername("tied_one");
        LeaderboardEntry tiedSecond = leaderboardEntry(3L, 13L, 80.0, 8, 0);
        tiedSecond.getUser().setUsername("tied_two");

        when(challengeRepository.findById(3L)).thenReturn(Optional.of(challenge));
        when(leaderboardEntryRepository.findTopWithUserByChallengeId(any(), any(Pageable.class)))
                .thenReturn(List.of(first, tiedFirst, tiedSecond));
        when(leaderboardEntryRepository.countParticipants(3L)).thenReturn(3L);
        when(leaderboardEntryRepository.findByChallengeIdAndUserId(3L, 12L))
                .thenReturn(Optional.of(tiedFirst));
        // The existing "Your position" endpoint uses competition rank for score ties,
        // while the visible list intentionally remains sequential (1, 2, 3).
        when(leaderboardEntryRepository.getUserRank(3L, 12L)).thenReturn(2);

        GetLeaderboardRequest request = GetLeaderboardRequest.builder()
                .challengeId(3L)
                .viewerUserId(99L)
                .userId(12L)
                .limit(10)
                .type(GetLeaderboardRequest.LeaderboardType.OVERALL)
                .build();

        LeaderboardResponse response = service.getLeaderboard(request);
        LeaderboardResponse.UserRankDTO directPosition = service.getUserRank(3L, 12L, 99L);

        assertEquals(List.of(11L, 12L, 13L),
                response.getEntries().stream().map(LeaderboardResponse.LeaderboardEntryDTO::getUserId).toList());
        assertEquals(List.of(1L, 2L, 3L),
                response.getEntries().stream().map(LeaderboardResponse.LeaderboardEntryDTO::getRank).toList());
        assertEquals(List.of(100.0, 80.0, 80.0),
                response.getEntries().stream().map(LeaderboardResponse.LeaderboardEntryDTO::getScore).toList());
        assertEquals(List.of(2L, 20L, 0L),
                response.getEntries().stream().map(LeaderboardResponse.LeaderboardEntryDTO::getGloriesCount).toList());
        assertEquals(2L, response.getCurrentUserRank().getRank());
        assertEquals(2L, directPosition.getRank());
        assertEquals(3L, directPosition.getTotalParticipants());
    }

    private LeaderboardEntry leaderboardEntry(Long challengeId, Long userId, double score, int submissions, int glories) {
        Challenge challenge = new Challenge();
        challenge.setId(challengeId);
        User user = new User();
        user.setId(userId);

        LeaderboardEntry entry = new LeaderboardEntry(challenge, user);
        entry.setScore(score);
        entry.setSubmissionCount(submissions);
        entry.setGloryPoints(glories);
        return entry;
    }
}
