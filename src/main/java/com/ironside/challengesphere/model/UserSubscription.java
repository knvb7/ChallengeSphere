package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "user_subscriptions", schema = "challengesphere")
@Data
@NoArgsConstructor
public class UserSubscription {
    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider = "REVENUECAT";

    @Column(name = "app_user_id", nullable = false, unique = true)
    private String appUserId;

    @Column(name = "entitlement_id", nullable = false, length = 100)
    private String entitlementId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "store", length = 32)
    private String store;

    @Column(name = "environment", length = 32)
    private String environment;

    @Column(name = "expires_at")
    private Timestamp expiresAt;

    @Column(name = "will_renew", nullable = false)
    private Boolean willRenew = false;

    @Column(name = "last_synced_at", nullable = false)
    private Timestamp lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        createdAt = now;
        updatedAt = now;
        if (lastSyncedAt == null) lastSyncedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
