package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class ChallengeCreateRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Description is required")
    private String description;

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

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> coverPhotoUrls; // GCS URLs after upload via presigned URL flow

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> mediaUrls; // Backward-compatible alias for challenge cover photos

    private String mediaUrl; // Backward-compatible single cover photo alias

    private List<Long> invitedUserIds;
}
