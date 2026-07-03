package com.ironside.challengesphere.repository.projection;

import java.time.Instant;

public interface SubmissionPreviewProjection {
    Long getChallengeId();
    Long getSubmissionId();
    Long getUserId();
    String getMediaUrls();
    Instant getSubmissionTime();
    Long getTotalCount();
    Long getRowNumber();
}
