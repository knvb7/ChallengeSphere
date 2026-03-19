package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RedeemInviteRequest {

    @NotBlank(message = "Invite code is required")
    private String inviteCode;

    @NotNull(message = "User ID is required")
    private Long userId;
}

