package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;

@Entity
@Table(name = "submissions")
@Data
public class Submission {
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

    @Column(name = "proof_payload", columnDefinition = "TEXT")
    private String proofPayload;

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

    @Column(name = "glories_count", nullable = false)
    private Long gloriesCount = 0L;

    @Column(name = "comments_count", nullable = false)
    private Long commentsCount = 0L;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;
}
