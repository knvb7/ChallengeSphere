package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "challenge_crew_lifecycle")
@Data
@NoArgsConstructor
public class ChallengeCrewLifecycle {
    @Id
    @Column(name = "challenge_id")
    private Long challengeId;

    @Column(name = "started_at")
    private Timestamp startedAt;

    @Column(name = "started_by_user_id")
    private Long startedByUserId;

    @Column(name = "rematch_of_challenge_id")
    private Long rematchOfChallengeId;

    @Column(name = "recap_ready_at")
    private Timestamp recapReadyAt;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;
}
