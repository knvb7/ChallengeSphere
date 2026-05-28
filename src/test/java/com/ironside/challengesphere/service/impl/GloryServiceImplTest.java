package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GloryServiceImplTest {

    @Mock
    private GloryRepository gloryRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private UserChallengeRepository userChallengeRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisCacheServiceImpl cacheService;

    @Mock
    private NotificationService notificationService;

    private GloryServiceImpl gloryService;

    @BeforeEach
    void setUp() {
        gloryService = new GloryServiceImpl();
        ReflectionTestUtils.setField(gloryService, "gloryRepository", gloryRepository);
        ReflectionTestUtils.setField(gloryService, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(gloryService, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(gloryService, "userChallengeRepository", userChallengeRepository);
        ReflectionTestUtils.setField(gloryService, "userBlockRepository", userBlockRepository);
        ReflectionTestUtils.setField(gloryService, "userRepository", userRepository);
        ReflectionTestUtils.setField(gloryService, "cacheService", cacheService);
        ReflectionTestUtils.setField(gloryService, "notificationService", notificationService);
    }

    @Test
    void toggleGloryInvalidatesFeedCachesSoRefreshUsesFreshLikeState() throws Exception {
        Submission submission = submission(100L, 2L, 10L);

        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(userBlockRepository.existsActiveBlockBetween(1L, 2L)).thenReturn(false);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(publicChallenge(10L)));
        when(gloryRepository.findBySubmissionIdAndUserId(100L, 1L)).thenReturn(Optional.empty());
        when(gloryRepository.save(any(Glory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean isGiven = gloryService.toggleGlory(100L, 1L);

        assertTrue(isGiven);
        verify(submissionRepository).incrementGloryCount(100L);
        verify(cacheService).invalidateGloryCaches(100L);
        verify(cacheService).invalidateAllFeeds();
        verify(notificationService).notifyGlory(submission, 1L);
    }

    @Test
    void toggleUngloryInvalidatesFeedCachesWhenItClearsAnActiveGlory() throws Exception {
        Submission submission = submission(100L, 2L, 10L);
        Glory existingGlory = activeGlory(100L, 1L);

        when(submissionRepository.findById(100L)).thenReturn(Optional.of(submission));
        when(userBlockRepository.existsActiveBlockBetween(1L, 2L)).thenReturn(false);
        when(challengeRepository.findById(10L)).thenReturn(Optional.of(publicChallenge(10L)));
        when(gloryRepository.findBySubmissionIdAndUserId(100L, 1L)).thenReturn(Optional.of(existingGlory));
        when(gloryRepository.save(any(Glory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean isUngloried = gloryService.toggleUnglory(100L, 1L);

        assertTrue(isUngloried);
        verify(submissionRepository).decrementGloryCount(100L);
        verify(cacheService).invalidateGloryCaches(100L);
        verify(cacheService).invalidateAllFeeds();
    }

    private Submission submission(Long id, Long userId, Long challengeId) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setUserId(userId);
        submission.setChallengeId(challengeId);
        submission.setDayNumber(1);
        submission.setDescription("Daily proof");
        submission.setMediaUrls("proof.jpg");
        submission.setIsPrivate(false);
        submission.setSubmissionTime(new Timestamp(System.currentTimeMillis()));
        submission.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        submission.setActive(true);
        submission.setGloriesCount(0L);
        submission.setCommentsCount(0L);
        return submission;
    }

    private Challenge publicChallenge(Long id) {
        Challenge challenge = new Challenge();
        challenge.setId(id);
        challenge.setTitle("Public Challenge");
        challenge.setDescription("Challenge description");
        challenge.setCreatorId(2L);
        challenge.setCurrentOwnerId(2L);
        challenge.setStartDate(LocalDate.now());
        challenge.setNumberOfDays(7);
        challenge.setIsPrivate(false);
        challenge.setActive(true);
        challenge.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        return challenge;
    }

    private Glory activeGlory(Long submissionId, Long userId) {
        Glory glory = new Glory();
        glory.setSubmissionId(submissionId);
        glory.setUserId(userId);
        glory.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        glory.setActive(true);
        glory.setUngloried(false);
        return glory;
    }
}
