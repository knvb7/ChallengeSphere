package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeInvitesResponse {
    private Long challengeId;
    private List<InviteLinkResponse> invites;
    private int totalCount;
}

