package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RedeemInviteRequest {

    @NotBlank(message = "Invite code is required")
    private String inviteCode;

    private Long userId;
}
