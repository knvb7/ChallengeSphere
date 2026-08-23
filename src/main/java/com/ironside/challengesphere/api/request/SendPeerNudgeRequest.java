package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendPeerNudgeRequest {
    @NotBlank(message = "Recipient user id is required")
    private String recipientUserId;
}
