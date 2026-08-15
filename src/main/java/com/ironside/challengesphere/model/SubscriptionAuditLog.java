package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "subscription_audit_log", schema = "challengesphere")
@Data
@NoArgsConstructor
public class SubscriptionAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "source_reference")
    private String sourceReference;

    @Column(name = "action", nullable = false, length = 48)
    private String action;

    @Column(name = "previous_status", length = 48)
    private String previousStatus;

    @Column(name = "new_status", length = 48)
    private String newStatus;

    @Column(name = "previous_premium", nullable = false)
    private Boolean previousPremium;

    @Column(name = "new_premium", nullable = false)
    private Boolean newPremium;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "event_at", nullable = false)
    private Timestamp eventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = new Timestamp(System.currentTimeMillis());
    }
}
