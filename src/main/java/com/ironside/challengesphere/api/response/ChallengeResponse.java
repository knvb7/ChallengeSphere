package com.ironside.challengesphere.api.response;

import lombok.Data;
import java.sql.Timestamp;

@Data
public class ChallengeResponse {
    private Long id;
    private String title;
    private String description;
    private Long creatorUserId;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private boolean active;
    private String shareCode;
    private Timestamp endDate;
    private int numberOfDays;
    private String reminderTime;
}