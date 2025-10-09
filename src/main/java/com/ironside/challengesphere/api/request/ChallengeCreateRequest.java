package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.time.LocalDate;

@Data
public class ChallengeCreateRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Creator user ID is required")
    private Long creatorUserId;

    private String hashtags;
    private String categories;
    private String benefits;
    private Boolean isPrivate;
    private LocalDate startDate;

    @NotNull(message = "Number of days is required")
    @Positive(message = "Number of days must be positive")
    private Integer numberOfDays;

    private String reminderTime; // e.g. "08:00 AM"
}