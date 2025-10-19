package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class GetChallengesByHashtagRequest {
    @NotEmpty(message = "At least one hashtag is required")
    private List<String> hashtags;

    @Min(value = 1, message = "Page must be at least 1")
    private int page = 1;

    @Min(value = 1, message = "Page size must be at least 1")
    private int pageSize = 10;
}
