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

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "payload_sha256", length = 64)
    private String payloadSha256;

    @Column(name = "processed_at")
    private Timestamp processedAt;

    @Column(name = "received_at", nullable = false)
    private Timestamp receivedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = new Timestamp(System.currentTimeMillis());
    }
}
