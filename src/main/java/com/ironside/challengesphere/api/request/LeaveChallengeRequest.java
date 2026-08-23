package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class LeaveChallengeRequest {
    @NotBlank(message = "Challenge ID is required")
    private String challengeId;

    private List<String> userIds;
}
