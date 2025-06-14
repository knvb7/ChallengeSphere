package com.ironside.challengesphere.api.request;

import lombok.Data;

import java.sql.Timestamp;

@Data
public class ChallengeUpdateRequest {
    private String title;
    private String description;
    private Timestamp endDate;
    private Integer numberOfDays;
    private String reminderTime;
    private Boolean active;
}

