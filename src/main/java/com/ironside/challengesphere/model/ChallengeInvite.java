package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "challenge_invites")
@Data
@NoArgsConstructor
public class ChallengeInvite {

    @Id
    private String id;

    @Column(name = "challenge_id", nullable = false)
    private String challengeId;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "recipient_user_id")
    private String recipientUserId;

    @Column(name = "invite_code", nullable = false, unique = true, length = 12)
    private String inviteCode;

    @Column(name = "expires_at")
    private Timestamp expiresAt;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    private Integer useCount = 0;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;
}
