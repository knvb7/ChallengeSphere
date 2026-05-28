package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;

@Entity
@Table(name = "push_device_tokens", schema = "challengesphere")
@Data
@NoArgsConstructor
public class PushDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expo_push_token", nullable = false, unique = true, length = 255)
    private String expoPushToken;

    @Column(name = "platform", nullable = false, length = 32)
    private String platform;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "last_seen_at", nullable = false)
    private Timestamp lastSeenAt;

    @Column(name = "last_daily_reminder_sent_date")
    private LocalDate lastDailyReminderSentDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        createdAt = now;
        updatedAt = now;
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
