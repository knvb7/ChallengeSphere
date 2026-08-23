package com.ironside.challengesphere.api.response;

import java.sql.Timestamp;

public record SubmissionPreviewItemResponse(
        String submissionId,
        String userId,
        String mediaUrl,
        Timestamp submissionTime
) {
}
