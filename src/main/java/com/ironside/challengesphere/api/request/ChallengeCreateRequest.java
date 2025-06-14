package com.ironside.challengesphere.api.request;

import lombok.Data;
import java.sql.Timestamp;

@Data
public class ChallengeCreateRequest {
    private String title;
    private String description;
    private Long creatorUserId;
    private Timestamp endDate;
    private int numberOfDays;
    private String reminderTime; // e.g. "08:00 AM"
}