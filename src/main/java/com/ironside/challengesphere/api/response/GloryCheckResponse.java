package com.ironside.challengesphere.api.response;

import lombok.Data;

@Data
public class GloryCheckResponse {
    private Long submissionId;
    private Long userId;
    private boolean hasGivenGlory;
    private boolean hasUngloried;

    public static GloryCheckResponse of(Long submissionId, Long userId, boolean hasGivenGlory) {
        GloryCheckResponse response = new GloryCheckResponse();
        response.setSubmissionId(submissionId);
        response.setUserId(userId);
        response.setHasGivenGlory(hasGivenGlory);
        return response;
    }

    public static GloryCheckResponse of(Long submissionId, Long userId, boolean hasGivenGlory, boolean hasUngloried) {
        GloryCheckResponse response = of(submissionId, userId, hasGivenGlory);
        response.setHasUngloried(hasUngloried);
        return response;
    }
}
