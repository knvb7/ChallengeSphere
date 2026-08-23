package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateInviteLinkRequest {

    @NotBlank(message = "Challenge ID is required")
    private String challengeId;

    private String userId;

    /**
     * Optional sole recipient for a direct private-challenge invitation.
     * When omitted, the generated code can be shared with eligible users.
     */
    private String recipientUserId;

    /**
     * Maximum number of times a shareable invite can be used. Null means
     * unlimited. Recipient-bound private invitations are always single-use.
     */
    private Integer maxUses;

    /**
     * Number of hours until the invite expires. Null uses the 30-day service default.
     */
    private Integer expiresInHours;
}
