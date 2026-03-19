package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemInviteResponse {
    private Long challengeId;
    private String challengeTitle;
    private Long userId;
    private String message;
    private boolean success;

    public static RedeemInviteResponse success(Long challengeId, String challengeTitle, Long userId) {
        return RedeemInviteResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .userId(userId)
                .message("Successfully joined the challenge via invite link")
                .success(true)
                .build();
    }

    public static RedeemInviteResponse failure(String message) {
        return RedeemInviteResponse.builder()
                .message(message)
                .success(false)
                .build();
    }
}

