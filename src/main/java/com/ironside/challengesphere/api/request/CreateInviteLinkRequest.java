package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateInviteLinkRequest {

    @NotNull(message = "Challenge ID is required")
    private Long challengeId;

    @NotNull(message = "User ID is required")
    private Long userId;

    /**
     * Maximum number of times this invite can be used. Null means unlimited.
     */
    private Integer maxUses;

    /**
     * Number of hours until the invite expires. Null means no expiration (defaults to 7 days).
     */
    private Integer expiresInHours;
}

