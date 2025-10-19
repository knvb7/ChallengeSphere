package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GetChallengeParticipantsRequest {
    @NotNull(message = "Challenge ID is required")
    private Long challengeId;
}