package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SendPeerNudgeRequest {
    @NotNull(message = "Recipient user id is required")
    @Positive(message = "Recipient user id must be positive")
    private Long recipientUserId;
}
