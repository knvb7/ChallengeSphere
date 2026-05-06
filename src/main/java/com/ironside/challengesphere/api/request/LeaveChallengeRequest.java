package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class LeaveChallengeRequest {
    @NotNull(message = "Challenge ID is required")
    private Long challengeId;

    private List<Long> userIds;
}
