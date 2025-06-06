package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
//@Table(name = "daily_submissions",
//       uniqueConstraints = @UniqueConstraint(columnNames = {"participant_id", "submission_date"}),
//       indexes = {
//           @Index(name = "idx_submissions_participant", columnList = "participant_id"),
//           @Index(name = "idx_submissions_date", columnList = "submission_date")
//       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DailySubmission {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "submission_id")
    private Long submissionId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private ChallengeParticipant participant;
    
    @Column(name = "media_url", nullable = false)
    private String mediaUrl;
    
    @Column(name = "notes")
    private String notes;
    
    @Column(name = "submission_date", nullable = false)
    private LocalDate submissionDate;
    
    @Column(name = "submitted_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime submittedAt;
    
    @Column(name = "is_verified", nullable = false)
    private Boolean isVerified = false;
    
    @Column(name = "verification_code")
    private String verificationCode;
    
    @PrePersist
    protected void onCreate() {
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
        if (submissionDate == null) {
            submissionDate = LocalDate.now();
        }
        if (isVerified == null) {
            isVerified = false;
        }
    }
    @PreUpdate
    protected void onUpdate() {
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
        if (submissionDate == null) {
            submissionDate = LocalDate.now();
        }
        if (isVerified == null) {
            isVerified = false;
        }
    }
}