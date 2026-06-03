package com.ironside.challengesphere.api.response;

public record DeleteSubmissionResponse(
        boolean success,
        String message,
        Long submissionId
) {
}
