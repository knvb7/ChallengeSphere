package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.sql.Timestamp;

@Entity
@Table(
        name = "user_integrations",
        schema = "challengesphere",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_integrations_user_provider", columnNames = {"user_id", "provider"})
)
@Getter
@Setter
@NoArgsConstructor
public class UserIntegration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "external_user_id", length = 128)
    private String externalUserId;

    @Column(name = "external_username", length = 255)
    private String externalUsername;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "access_token_expires_at")
    private Timestamp accessTokenExpiresAt;

    @Column(name = "scopes", length = 512)
    private String scopes;

    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "connected_at")
    private Timestamp connectedAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "last_synced_at")
    private Timestamp lastSyncedAt;

}
