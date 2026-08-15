package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteLinkResponse {
    private Long inviteId;
    private String inviteCode;
    private String inviteUrl;
    private Long challengeId;
    private String challengeTitle;
    private Long recipientUserId;
    private Timestamp expiresAt;
    private Integer maxUses;
    private Integer useCount;
    private boolean active;
    private Timestamp createdAt;
    private String inviterName;
    private String inviterUsername;
    private String challengeDescription;
    private String challengeRules;
    private String challengeBenefits;
    private String challengeCategories;
    private String challengeMode;
    private String proofMode;
    private LocalDate challengeStartDate;
    private Integer challengeDurationDays;
    private Integer participantCount;
    private Integer acceptedCount;
    private Integer pendingCount;
    private Integer remainingUses;
    private Boolean privateChallenge;

    public static InviteLinkResponse from(Long inviteId, String inviteCode, String inviteUrl,
                                           Long challengeId, String challengeTitle,
                                           Timestamp expiresAt, Integer maxUses,
                                           Integer useCount, boolean active, Timestamp createdAt) {
        return InviteLinkResponse.builder()
                .inviteId(inviteId)
                .inviteCode(inviteCode)
                .inviteUrl(inviteUrl)
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .expiresAt(expiresAt)
                .maxUses(maxUses)
                .useCount(useCount)
                .active(active)
                .createdAt(createdAt)
                .build();
    }
}
