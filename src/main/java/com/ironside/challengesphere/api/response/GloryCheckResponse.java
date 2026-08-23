package com.ironside.challengesphere.api.response;

import lombok.Data;

@Data
public class GloryCheckResponse {
    private String submissionId;
    private String userId;
    private boolean hasGivenGlory;
    private boolean hasUngloried;

    public static GloryCheckResponse of(String submissionId, String userId, boolean hasGivenGlory) {
        GloryCheckResponse response = new GloryCheckResponse();
        response.setSubmissionId(submissionId);
        response.setUserId(userId);
        response.setHasGivenGlory(hasGivenGlory);
        return response;
    }

    public static GloryCheckResponse of(String submissionId, String userId, boolean hasGivenGlory, boolean hasUngloried) {
        GloryCheckResponse response = of(submissionId, userId, hasGivenGlory);
        response.setHasUngloried(hasUngloried);
        return response;
    }
}
