package com.ironside.challengesphere.api.response;

import java.util.List;

public record ChallengeSubmissionPreviewResponse(
        Long challengeId,
        List<SubmissionPreviewItemResponse> submissions,
        long totalCount
) {
}
