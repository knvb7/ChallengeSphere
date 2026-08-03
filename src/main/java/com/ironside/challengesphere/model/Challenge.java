package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.sql.Timestamp;

@Entity
@Table(name = "challenges")
@Data
@NoArgsConstructor
public class Challenge {
    public static final String MODE_DAILY = "DAILY";
    public static final String MODE_ONE_OFF = "ONE_OFF";
    public static final String PROOF_MODE_FLEXIBLE = "FLEXIBLE";
    public static final String PROOF_MODE_PHOTO_REQUIRED = "PHOTO_REQUIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hashtags")
    private String hashtags;

    @Column(name = "categories")
    private String categories;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "benefits")
    private String benefits;

    @Column(name = "private", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean isPrivate = false;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "active", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean active = true;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Transient
    private String creatorUsername;

    @Transient
    private String creatorProfilePicUrl;

    @Column(name = "current_owner_id", nullable = false)
    private Long currentOwnerId;

    @Column(name = "number_of_days", nullable = false)
    private Integer numberOfDays;

    @Column(name = "challenge_mode", nullable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'DAILY'")
    private String challengeMode = MODE_DAILY;

    @Column(name = "proof_mode", nullable = false, length = 24, columnDefinition = "VARCHAR(24) DEFAULT 'PHOTO_REQUIRED'")
    private String proofMode = PROOF_MODE_PHOTO_REQUIRED;

    @Column(name = "cover_photo_urls", columnDefinition = "TEXT")
    private String coverPhotoUrls; // Comma-separated S3 object keys or URLs, up to 5

    @Column(name = "official_template_key", length = 80)
    private String officialTemplateKey;

    @Column(name = "cohort_key", length = 120)
    private String cohortKey;

    public static boolean isOneOffMode(String challengeMode) {
        return MODE_ONE_OFF.equalsIgnoreCase(challengeMode);
    }

    public static boolean requiresPhotoProof(String proofMode) {
        return PROOF_MODE_PHOTO_REQUIRED.equalsIgnoreCase(proofMode);
    }
}
