package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class ChallengeUpdateRequest {
    @Size(max = 255, message = "Title must be at most 255 characters")
    private String title;

    @Size(max = 5000, message = "Description must be at most 5000 characters")
    private String description;
    private String hashtags;
    private String categories;
    private String benefits;
    private Boolean isPrivate;
    private Timestamp endDate;
    @Min(value = 1, message = "Number of days must be at least 1")
    @Max(value = 365, message = "Number of days must not exceed 365")
    private Integer numberOfDays;
    private String challengeMode;
    private String proofMode;
    private String reminderTime;
    private Boolean active;
    @Size(max = 20, message = "A challenge can invite at most 20 users at once")
    private List<String> invitedUserIds;

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> coverPhotoUrls; // replaces all existing cover photos when provided

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> mediaUrls; // Backward-compatible alias for challenge cover photos

    private String mediaUrl; // Backward-compatible single cover photo alias
}
