package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class GetChallengesByHashtagRequest {
    private String currentUserId;

    @NotEmpty(message = "At least one hashtag is required")
    private List<String> hashtags;

    private String cursor;

    @Min(value = 1, message = "Limit must be at least 1")
    private int limit = 10;
}
