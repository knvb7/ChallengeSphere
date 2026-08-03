package com.ironside.challengesphere.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;

@Entity
@Table(name = "submissions")
@Data
public class Submission {
    public static final String MODERATION_PENDING = "PENDING";
    public static final String MODERATION_PROCESSING = "PROCESSING";
    public static final String MODERATION_PASSED = "PASSED";
    public static final String MODERATION_FLAGGED = "FLAGGED";
    public static final String MODERATION_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Column(name = "media_urls", columnDefinition = "TEXT")
    private String mediaUrls; // Comma-separated URLs

    @Column(name = "is_private", nullable = false)
    private Boolean isPrivate = false;

    @Column(name = "submission_time", nullable = false)
    private Timestamp submissionTime;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "moderation_status", nullable = false, length = 16)
    private String moderationStatus = MODERATION_PENDING;

    @Column(name = "moderation_started_at")
    @JsonIgnore
    private Timestamp moderationStartedAt;

    @Column(name = "moderated_at")
    private Timestamp moderatedAt;

    @Column(name = "moderation_error", length = 512)
    @JsonIgnore
    private String moderationError;

    @Column(name = "glories_count", nullable = false)
    private Long gloriesCount = 0L;

    @Column(name = "comments_count", nullable = false)
    private Long commentsCount = 0L;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;
}
