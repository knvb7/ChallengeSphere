package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateFeatureRequest {

    @NotNull(message = "Feature category is required")
    private Category category;

    @NotBlank(message = "Feature request is required")
    @Size(min = 10, max = 300, message = "Feature request must be between 10 and 300 characters")
    private String message;

    @Size(max = 80, message = "App version should not exceed 80 characters")
    private String appVersion;

    @Size(max = 80, message = "Platform should not exceed 80 characters")
    private String platform;

    @Size(max = 200, message = "Device info should not exceed 200 characters")
    private String deviceInfo;

    public enum Category {
        CHALLENGES,
        COMMUNITY,
        PROGRESS_AND_STATS,
        APP_EXPERIENCE,
        OTHER
    }
}
