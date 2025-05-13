package com.ironside.challengesphere.api.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class SubmissionResponse {
    private Long submissionId;
    private Long challengeId;
    private Long participantId;
    private String mediaUrl;
    private String notes;
    private LocalDate submissionDate;
    private OffsetDateTime submittedAt;
    private boolean isVerified;
    private UserProfileResponse submitter;
}
