package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class ChallengeUpdateRequest {
    private String title;
    private String description;
    private String hashtags;
    private String categories;
    private String benefits;
    private Boolean isPrivate;
    private Timestamp endDate;
    private Integer numberOfDays;
    private String challengeMode;
    private String reminderTime;
    private Boolean active;
    private List<Long> invitedUserIds;

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> coverPhotoUrls; // replaces all existing cover photos when provided

    @Size(max = 5, message = "A challenge can have at most 5 cover photos")
    private List<String> mediaUrls; // Backward-compatible alias for challenge cover photos

    private String mediaUrl; // Backward-compatible single cover photo alias
}
