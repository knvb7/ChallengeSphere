package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RedeemInviteRequest {

    @NotBlank(message = "Invite code is required")
    @Size(min = 8, max = 12, message = "Invite code is invalid")
    @Pattern(regexp = "[A-Za-z0-9]+", message = "Invite code is invalid")
    private String inviteCode;

    private String userId;
}
