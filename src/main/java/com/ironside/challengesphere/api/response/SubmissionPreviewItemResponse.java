package com.ironside.challengesphere.api.response;

import java.sql.Timestamp;

public record SubmissionPreviewItemResponse(
        Long submissionId,
        Long userId,
        String mediaUrl,
        Timestamp submissionTime
) {
}
