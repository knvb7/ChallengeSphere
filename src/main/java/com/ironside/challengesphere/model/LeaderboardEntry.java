package com.ironside.challengesphere.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * LeaderboardEntry Model
 * Persists leaderboard data for challenges to ensure durability.
 * Redis is used as a cache layer on top of this for fast reads.
 */
@Entity
@Table(name = "leaderboard_entries",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"challenge_id", "user_id"}, name = "uk_leaderboard_challenge_user")
    },
    indexes = {
        @Index(name = "idx_leaderboard_challenge_score", columnList = "challenge_id, score DESC"),
        @Index(name = "idx_leaderboard_user", columnList = "user_id"),
        @Index(name = "idx_leaderboard_challenge_rank", columnList = "challenge_id, current_rank")
    }
)
@Data
public class LeaderboardEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Double score = 0.0;

    @Column(name = "current_rank")
    private Integer currentRank;

    @Column(name = "previous_rank")
    private Integer previousRank;

    @Column(name = "submission_count")
    private Integer submissionCount = 0;

    @Column(name = "glory_points")
    private Integer gloryPoints = 0;

    @Column(name = "streak_days")
    private Integer streakDays = 0;

    @Column(name = "best_score")
    private Double bestScore = 0.0;

    @Column(name = "last_submission_at")
    private LocalDateTime lastSubmissionAt;

    @Column(name = "first_submission_at")
    private LocalDateTime firstSubmissionAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private LeaderboardStatus status = LeaderboardStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum LeaderboardStatus {
        ACTIVE,      // Currently participating
        COMPLETED,   // Finished the challenge
        DISQUALIFIED // Removed from leaderboard (e.g., cheating)
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (firstSubmissionAt == null) {
            firstSubmissionAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public LeaderboardEntry() {}

    public LeaderboardEntry(Challenge challenge, User user) {
        this.challenge = challenge;
        this.user = user;
    }

    public void setScore(Double score) {
        this.score = score;
        // Update best score if current score is higher
        if (this.bestScore == null || score > this.bestScore) {
            this.bestScore = score;
        }
    }

    public void setCurrentRank(Integer currentRank) {
        // Store previous rank before updating
        if (this.currentRank != null) {
            this.previousRank = this.currentRank;
        }
        this.currentRank = currentRank;
    }

    public Integer getRankChange() {
        if (previousRank == null || currentRank == null) {
            return 0;
        }
        return previousRank - currentRank; // Lower rank number is better
    }

    public boolean hasImproved() {
        Integer change = getRankChange();
        return change != null && change > 0;
    }

    public void updateScore(double newScore) {
        setScore(newScore);
        this.lastSubmissionAt = LocalDateTime.now();
    }

    /**
     * Increment submission count and update last submission time
     */
    public void incrementSubmissionCount() {
        this.submissionCount = (this.submissionCount == null ? 0 : this.submissionCount) + 1;
        this.lastSubmissionAt = LocalDateTime.now();
    }

    /**
     * Add glory points to user
     */
    public void addGloryPoints(int points) {
        this.gloryPoints = (this.gloryPoints == null ? 0 : this.gloryPoints) + points;
    }

    @Override
    public String toString() {
        return "LeaderboardEntry{" +
                "id=" + id +
                ", challengeId=" + (challenge != null ? challenge.getId() : null) +
                ", userId=" + (user != null ? user.getId() : null) +
                ", score=" + score +
                ", currentRank=" + currentRank +
                ", status=" + status +
                '}';
    }
}
