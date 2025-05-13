package com.ironside.challengesphere.api.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ParticipantResponse {
    private Long participantId;
    private UserProfileResponse user;
    private OffsetDateTime joinedAt;
    private int submissionsCount;
    private int verifiedSubmissionsCount;
    private double completionPercentage;
    private int currentStreak;
    private int longestStreak;
}
