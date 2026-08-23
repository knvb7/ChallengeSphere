package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChallengeInvitesResponse extends CursorResponse {
    private String challengeId;
    private List<InviteLinkResponse> invites;
}
