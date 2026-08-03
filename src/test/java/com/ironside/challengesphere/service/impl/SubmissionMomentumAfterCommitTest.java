package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubmissionMomentumAfterCommitTest {

    @Test
    void schedulesMilestoneEvaluationOnlyAfterSubmissionCommit() {
        SubmissionServiceImpl service = new SubmissionServiceImpl();
        NotificationService notificationService = mock(NotificationService.class);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        LocalDate localDate = LocalDate.parse("2026-07-07");

        TransactionSynchronizationManager.initSynchronization();
        try {
            ReflectionTestUtils.invokeMethod(
                    service,
                    "notifyMomentumMilestonesAfterCommit",
                    7L,
                    11L,
                    localDate,
                    true
            );

            verify(notificationService, never())
                    .notifyMomentumMilestonesAfterSubmissionAsync(7L, 11L, localDate, true);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(notificationService)
                    .notifyMomentumMilestonesAfterSubmissionAsync(7L, 11L, localDate, true);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
