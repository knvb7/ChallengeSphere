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
    private String sourceChallengeId;
    private String challengeId;
    private int copiedInviteCount;
    private String message;
}
