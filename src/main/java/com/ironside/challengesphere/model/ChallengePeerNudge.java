package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "challenge_peer_nudges")
@Data
@NoArgsConstructor
public class ChallengePeerNudge {
    @Id
    private String id;

    @Column(name = "challenge_id", nullable = false)
    private String challengeId;

    @Column(name = "sender_user_id", nullable = false)
    private String senderUserId;

    @Column(name = "recipient_user_id", nullable = false)
    private String recipientUserId;

    @Column(name = "sent_at", nullable = false)
    private Timestamp sentAt;
}
