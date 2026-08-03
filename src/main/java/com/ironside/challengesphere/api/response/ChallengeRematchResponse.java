package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeRematchResponse {
    private Long sourceChallengeId;
    private Long challengeId;
    private int copiedInviteCount;
    private String message;
}
