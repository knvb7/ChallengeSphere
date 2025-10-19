package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.sql.Timestamp;

@Entity
@Table(name = "user_challenges")
@Data
@NoArgsConstructor
public class UserChallenge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "joined_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp joinedAt;

    @Column(name = "completed", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean completed = false;

    @Column(name = "completed_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Timestamp completedAt;

    @Column(name = "active", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean active = true;
}