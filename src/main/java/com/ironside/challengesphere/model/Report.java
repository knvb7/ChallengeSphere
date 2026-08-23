package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.sql.Timestamp;

@Entity
@Table(name = "reports")
@Data
@NoArgsConstructor
public class Report {

    @Id
    private String id;

    @Column(name = "submission_id")
    private String submissionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "reason", nullable = false, length = 100)
    private String reason;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "status", columnDefinition = "VARCHAR(20) DEFAULT 'PENDING'")
    private String status = "PENDING";

    @Column(name = "active", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean active = true;

    private String targetType;
    private String targetId;
    private String targetOwnerUserId;
    private String targetSnapshot;
    private String resolution;
    private String reviewedBy;
    private Timestamp reviewedAt;
}
