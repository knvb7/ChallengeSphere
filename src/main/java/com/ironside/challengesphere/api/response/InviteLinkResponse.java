package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

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
    private Timestamp expiresAt;
    private Integer maxUses;
    private Integer useCount;
    private boolean active;
    private Timestamp createdAt;

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

