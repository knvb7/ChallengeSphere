package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "user_preferences", schema = "challengesphere")
@Data
@NoArgsConstructor
public class UserPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // Notification preferences
    @Column(name = "push_notifications_enabled", nullable = false)
    private Boolean pushNotificationsEnabled = true;

    @Column(name = "email_notifications_enabled", nullable = false)
    private Boolean emailNotificationsEnabled = true;

    @Column(name = "notify_on_glory", nullable = false)
    private Boolean notifyOnGlory = false;

    @Column(name = "notify_on_comment", nullable = false)
    private Boolean notifyOnComment = true;

    @Column(name = "notify_on_follow", nullable = false)
    private Boolean notifyOnFollow = true;

    @Column(name = "notify_on_challenge_invite", nullable = false)
    private Boolean notifyOnChallengeInvite = true;

    @Column(name = "notify_on_challenge_update", nullable = false)
    private Boolean notifyOnChallengeUpdate = true;

    // Privacy preferences
    @Column(name = "private_profile", nullable = false)
    private Boolean privateProfile = false;

    @Column(name = "private_submissions", nullable = false)
    private Boolean privateSubmissions = false;

    @Column(name = "show_on_leaderboard", nullable = false)
    private Boolean showOnLeaderboard = true;

    @Column(name = "allow_challenge_invites", nullable = false)
    private Boolean allowChallengeInvites = true;

    // App preferences
    @Column(name = "dark_mode", nullable = false)
    private Boolean darkMode = false;

    @Column(name = "language", nullable = false, length = 10)
    private String language = "en";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
