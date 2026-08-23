package com.ironside.challengesphere.repository.projection;

import java.time.Instant;

public interface SubmissionPreviewProjection {
    String getChallengeId();
    String getSubmissionId();
    String getUserId();
    String getMediaUrls();
    Instant getSubmissionTime();
    Long getTotalCount();
    Long getRowNumber();
    Boolean getTotalCountExact();
    Boolean getHasMore();
}
