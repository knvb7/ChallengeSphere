package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class JoinChallengeRequest {
    @NotNull(message = "Challenge ID is required")
    private Long challengeId;

    @NotEmpty(message = "At least one user ID is required")
    private List<Long> userIds;
}

