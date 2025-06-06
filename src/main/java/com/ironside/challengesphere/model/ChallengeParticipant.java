package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
//@Table(name = "challenge_participants",
//       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "challenge_id"}),
//       indexes = {
//           @Index(name = "idx_participants_user", columnList = "user_id"),
//           @Index(name = "idx_participants_challenge", columnList = "challenge_id")
//       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeParticipant {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long participantId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;
    
    @Column(name = "joined_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime joinedAt;
    
    @Column(name = "is_accepted", nullable = false)
    private Boolean isAccepted = false;
    
    @Column(name = "streak_days")
    private Integer streakDays = 0;
    
    @Column(name = "longest_streak")
    private Integer longestStreak = 0;
    
    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = OffsetDateTime.now();
        }
        if (isAccepted == null) {
            isAccepted = false;
        }
        if (streakDays == null) {
            streakDays = 0;
        }
        if (longestStreak == null) {
            longestStreak = 0;
        }
    }
}