package com.ironside.challengesphere.api.response;

import java.util.List;

public record ChallengeSubmissionPreviewResponse(
        String challengeId,
        List<SubmissionPreviewItemResponse> submissions,
        long totalCount,
        boolean totalCountExact,
        boolean hasMore
) {
}
