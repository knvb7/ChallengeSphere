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
@Table(name = "feature_requests", schema = "challengesphere")
@Data
@NoArgsConstructor
public class FeatureRequest {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "message", nullable = false, length = 300)
    private String message;

    @Column(name = "app_version", length = 80)
    private String appVersion;

    @Column(name = "platform", length = 80)
    private String platform;

    @Column(name = "device_info", length = 200)
    private String deviceInfo;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "NEW";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
