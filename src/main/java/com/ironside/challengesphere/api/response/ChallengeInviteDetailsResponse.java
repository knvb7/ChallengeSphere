package com.ironside.challengesphere.api.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class ChallengeInviteDetailsResponse {
    private Long notificationId;
    private Long challengeId;
    private String challengeTitle;
    private String challengeDescription;
    private String challengeRules;
    private String challengeBenefits;
    private String challengeCategories;
    private String challengeMode;
    private String proofMode;
    private LocalDate challengeStartDate;
    private Integer challengeDurationDays;
    private Boolean privateChallenge;
    private Long inviterUserId;
    private String inviterName;
    private String inviterUsername;
    private Long creatorUserId;
    private String creatorName;
    private String creatorUsername;
}
