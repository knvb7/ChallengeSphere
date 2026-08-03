package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GloryNotificationAfterCommitTest {

    @Test
    void ownerCanGiveGloryAndNotificationSchedulesOnlyAfterCommit() throws Exception {
        SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
        GloryRepository gloryRepository = mock(GloryRepository.class);
        ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
        NotificationService notificationService = mock(NotificationService.class);

        Submission submission = new Submission();
        submission.setId(91L);
        submission.setUserId(3L);
        submission.setChallengeId(7L);
        submission.setActive(true);
        submission.setIsPrivate(false);

        Challenge challenge = new Challenge();
        challenge.setId(7L);
        challenge.setActive(true);
        challenge.setIsPrivate(false);

        when(submissionRepository.findById(91L)).thenReturn(Optional.of(submission));
        when(challengeRepository.findById(7L)).thenReturn(Optional.of(challenge));
        when(gloryRepository.findBySubmissionIdAndUserId(91L, 3L)).thenReturn(Optional.empty());
        when(gloryRepository.save(any(Glory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GloryServiceImpl service = new GloryServiceImpl();
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "gloryRepository", gloryRepository);
        ReflectionTestUtils.setField(service, "challengeRepository", challengeRepository);
        ReflectionTestUtils.setField(service, "userChallengeRepository", mock(UserChallengeRepository.class));
        ReflectionTestUtils.setField(service, "userBlockRepository", mock(UserBlockRepository.class));
        ReflectionTestUtils.setField(service, "cacheService", mock(RedisCacheServiceImpl.class));
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "leaderboardService", mock(LeaderboardService.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.toggleGlory(91L, 3L));
            verify(notificationService, never()).notifyGloryAsync(any(), any());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertTrue(synchronizations.size() >= 1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(notificationService).notifyGloryAsync(submission, 3L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
