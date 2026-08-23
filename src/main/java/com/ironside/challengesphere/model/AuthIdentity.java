package com.ironside.challengesphere.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "auth_identities",
        schema = "challengesphere",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_auth_identity_provider_subject", columnNames = {"provider", "subject"}),
                @UniqueConstraint(name = "uq_auth_identity_user_provider", columnNames = {"user_id", "provider"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class AuthIdentity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    @Column(name = "email_forwarding_enabled")
    private Boolean emailForwardingEnabled;

    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;
}
