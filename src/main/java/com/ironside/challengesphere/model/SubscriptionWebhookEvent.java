package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "subscription_webhook_events", schema = "challengesphere")
@Data
@NoArgsConstructor
public class SubscriptionWebhookEvent {
    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "app_user_id")
    private String appUserId;

    @Column(name = "environment")
    private String environment;

    @Column(name = "received_at", nullable = false)
    private Timestamp receivedAt;

    /**
     * DynamoDB-only processing state. These fields deliberately remain
     * transient to the legacy PostgreSQL schema so historical webhook rows can
     * still be imported without a relational schema change.
     */
    @Transient
    private String processingStatus;

    @Transient
    private String claimToken;

    @Transient
    private Timestamp leaseExpiresAt;

    @Transient
    private Timestamp lastAttemptAt;

    @Transient
    private Timestamp completedAt;

    @Transient
    private Integer attemptCount;

    @Transient
    private String lastFailure;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = new Timestamp(System.currentTimeMillis());
    }
}
