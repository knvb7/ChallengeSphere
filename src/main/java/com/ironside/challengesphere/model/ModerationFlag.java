package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.sql.Timestamp;

@Entity
@Table(name = "moderation_flags")
@Getter
@Setter
@NoArgsConstructor
public class ModerationFlag {

    @Id
    private String id;

    @Column(name = "submission_id", nullable = false)
    private String submissionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    // Provider labels can include a parent/name path and are not a fixed enum.
    @Column(name = "category", nullable = false, columnDefinition = "TEXT")
    private String category;

    // LIKELY or VERY_LIKELY (we don't store POSSIBLE/UNLIKELY rows).
    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "media_url", columnDefinition = "TEXT")
    private String mediaUrl;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "reviewed_at")
    private Timestamp reviewedAt;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    // APPROVED, REMOVED, BANNED_USER — null while pending.
    @Column(name = "resolution", length = 32)
    private String resolution;
}
