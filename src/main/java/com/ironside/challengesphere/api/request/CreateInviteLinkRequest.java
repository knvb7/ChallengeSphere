package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateInviteLinkRequest {

    @NotNull(message = "Challenge ID is required")
    private Long challengeId;

    private Long userId;

    /**
     * Maximum number of times this invite can be used. Null means unlimited.
     */
    private Integer maxUses;

    /**
     * Number of hours until the invite expires. Null uses the 30-day service default.
     */
    private Integer expiresInHours;
}
