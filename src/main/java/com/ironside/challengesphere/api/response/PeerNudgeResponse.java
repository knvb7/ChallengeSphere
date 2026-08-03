package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeerNudgeResponse {
    private Long challengeId;
    private Long recipientUserId;
    private Timestamp sentAt;
    private Timestamp nextAllowedAt;
    private String message;
}
