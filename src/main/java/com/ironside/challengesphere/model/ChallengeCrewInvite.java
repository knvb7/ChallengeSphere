package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(
        name = "challenge_crew_invites",
        uniqueConstraints = @UniqueConstraint(columnNames = {"challenge_id", "invited_user_id"})
)
@Data
@NoArgsConstructor
public class ChallengeCrewInvite {
    @Id
    private String id;

    @Column(name = "challenge_id", nullable = false)
    private String challengeId;

    @Column(name = "invited_user_id", nullable = false)
    private String invitedUserId;

    @Column(name = "invited_by_user_id")
    private String invitedByUserId;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;
}
