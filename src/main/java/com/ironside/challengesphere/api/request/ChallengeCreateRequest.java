package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class ChallengeCreateRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must be at most 255 characters")
    private String title;

    @NotBlank(message = "Description is required")
    @Size(max = 5000, message = "Description must be at most 5000 characters")
    private String description;

    private Long creatorUserId;

    private String hashtags;

    private String categories;

    private List<String> categoryValues;

    private String benefits;

    private String rules;
    private Boolean isPrivate;
    private LocalDate startDate;

    @NotNull(message = "Number of days is required")
    @Positive(message = "Number of days must be positive")
    @Max(value = 365, message = "Number of days must not exceed 365")
    private Integer numberOfDays;

    private String challengeMode;

    private String reminderTime; // e.g. "08:00 AM"

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> coverPhotoUrls; // S3 object keys or URLs after upload via presigned URL flow

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> mediaUrls; // Backward-compatible alias for challenge cover photos

    private String mediaUrl; // Backward-compatible single cover photo alias

    @Size(max = 20, message = "A challenge can invite at most 20 users at once")
    private List<Long> invitedUserIds;
}
