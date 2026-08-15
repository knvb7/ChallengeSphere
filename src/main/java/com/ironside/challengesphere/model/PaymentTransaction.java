package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Entity
@Table(name = "payment_transactions", schema = "challengesphere")
@Data
@NoArgsConstructor
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_transaction_id", nullable = false)
    private String providerTransactionId;

    @Column(name = "original_transaction_id")
    private String originalTransactionId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "entitlement_id", length = 100)
    private String entitlementId;

    @Column(name = "status", nullable = false, length = 48)
    private String status;

    @Column(name = "last_event_type", nullable = false, length = 100)
    private String lastEventType;

    @Column(name = "store", nullable = false, length = 32)
    private String store;

    @Column(name = "environment", length = 32)
    private String environment;

    @Column(name = "amount", precision = 14, scale = 4)
    private BigDecimal amount;

    @Column(name = "amount_usd", precision = 14, scale = 4)
    private BigDecimal amountUsd;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "period_type", length = 32)
    private String periodType;

    @Column(name = "purchased_at")
    private Timestamp purchasedAt;

    @Column(name = "expires_at")
    private Timestamp expiresAt;

    @Column(name = "cancellation_reason", length = 64)
    private String cancellationReason;

    @Column(name = "expiration_reason", length = 64)
    private String expirationReason;

    @Column(name = "last_event_id", nullable = false)
    private String lastEventId;

    @Column(name = "last_event_at", nullable = false)
    private Timestamp lastEventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
