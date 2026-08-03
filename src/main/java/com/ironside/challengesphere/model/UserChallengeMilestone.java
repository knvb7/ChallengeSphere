package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "user_challenge_milestones", schema = "challengesphere")
@Data
@NoArgsConstructor
public class UserChallengeMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "milestone_code", nullable = false, length = 40)
    private String milestoneCode;

    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "claimed_at", nullable = false, insertable = false, updatable = false)
    private Timestamp claimedAt;
}
