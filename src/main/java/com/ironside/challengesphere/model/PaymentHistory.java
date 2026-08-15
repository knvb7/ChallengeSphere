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

import java.math.BigDecimal;
import java.sql.Timestamp;

@Entity
@Table(name = "payment_history", schema = "challengesphere")
@Data
@NoArgsConstructor
public class PaymentHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "webhook_event_id", nullable = false, unique = true)
    private String webhookEventId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "status", nullable = false, length = 48)
    private String status;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "original_transaction_id")
    private String originalTransactionId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "new_product_id")
    private String newProductId;

    @Column(name = "entitlement_id", length = 100)
    private String entitlementId;

    @Column(name = "store", length = 32)
    private String store;

    @Column(name = "environment", length = 32)
    private String environment;

    @Column(name = "amount", precision = 14, scale = 4)
    private BigDecimal amount;

    @Column(name = "amount_usd", precision = 14, scale = 4)
    private BigDecimal amountUsd;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "tax_percentage", precision = 7, scale = 6)
    private BigDecimal taxPercentage;

    @Column(name = "commission_percentage", precision = 7, scale = 6)
    private BigDecimal commissionPercentage;

    @Column(name = "takehome_percentage", precision = 7, scale = 6)
    private BigDecimal takehomePercentage;

    @Column(name = "period_type", length = 32)
    private String periodType;

    @Column(name = "presented_offering_id")
    private String presentedOfferingId;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "cancellation_reason", length = 64)
    private String cancellationReason;

    @Column(name = "expiration_reason", length = 64)
    private String expirationReason;

    @Column(name = "purchased_at")
    private Timestamp purchasedAt;

    @Column(name = "expires_at")
    private Timestamp expiresAt;

    @Column(name = "event_at", nullable = false)
    private Timestamp eventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = new Timestamp(System.currentTimeMillis());
    }
}
