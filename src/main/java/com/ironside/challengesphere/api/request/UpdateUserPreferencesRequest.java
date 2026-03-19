package com.ironside.challengesphere.api.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UpdateUserPreferencesRequest {

    // Notification preferences
    private Boolean pushNotificationsEnabled;
    private Boolean emailNotificationsEnabled;
    private Boolean notifyOnGlory;
    private Boolean notifyOnComment;
    private Boolean notifyOnFollow;
    private Boolean notifyOnChallengeInvite;
    private Boolean notifyOnChallengeUpdate;

    // Privacy preferences
    private Boolean privateProfile;
    private Boolean privateSubmissions;
    private Boolean showOnLeaderboard;
    private Boolean allowChallengeInvites;

    // App preferences
    private Boolean darkMode;
    private String language;
}

