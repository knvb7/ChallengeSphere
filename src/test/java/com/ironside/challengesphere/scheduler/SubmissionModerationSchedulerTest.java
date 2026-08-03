package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.impl.SubmissionModerationJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionModerationSchedulerTest {

    @Test
    void sweepProcessesOnlyTheDurablyQueuedBatch() {
        SubmissionRepository repository = mock(SubmissionRepository.class);
        SubmissionModerationJob job = mock(SubmissionModerationJob.class);
        SubmissionModerationScheduler scheduler = new SubmissionModerationScheduler(repository, job, 2);
        when(repository.findSubmissionIdsAwaitingModeration(any(Pageable.class)))
                .thenReturn(List.of(11L, 12L));
        when(job.moderateIfPending(11L)).thenReturn(true);
        when(job.moderateIfPending(12L)).thenReturn(true);

        scheduler.moderatePendingSubmissionImages();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSubmissionIdsAwaitingModeration(pageable.capture());
        assertEquals(2, pageable.getValue().getPageSize());
        verify(job).moderateIfPending(11L);
        verify(job).moderateIfPending(12L);
    }
}
