package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "user_reports", schema = "challengesphere")
@Data
@NoArgsConstructor
public class UserReport {

    @Id
    private String id;

    @Column(name = "reporter_user_id", nullable = false)
    private String reporterUserId;

    @Column(name = "reported_user_id", nullable = false)
    private String reportedUserId;

    @Column(name = "reason", nullable = false, length = 128)
    private String reason;

    @Column(name = "details", length = 2000)
    private String details;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "OPEN";

    @Column(name = "resolution", length = 64)
    private String resolution;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Timestamp reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
