package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class GetChallengeParticipantsRequest {
    @NotBlank(message = "Challenge ID is required")
    private String challengeId;

    private String currentUserId;

    @Min(1)
    @Max(100)
    private int limit = 50;

    private String cursor;
}
