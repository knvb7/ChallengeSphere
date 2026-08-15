package com.ironside.challengesphere.api.response;

import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;

@Data
@Builder
public class SubscriptionStatusResponse {
    private String tier;
    private boolean premium;
    private boolean purchasesEnabled;
    private String appUserId;
    private String productId;
    private String status;
    private Timestamp expiresAt;
    private boolean willRenew;
    private long activePublicChallenges;
    private int publicChallengeLimit;
    private long activePrivateChallenges;
    private int privateChallengeLimit;
}
