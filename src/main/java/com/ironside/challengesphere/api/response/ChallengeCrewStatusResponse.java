package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeCrewStatusResponse {
    private String challengeId;
    private String state;
    private boolean privateChallenge;
    private boolean canManageCrew;
    private boolean canStart;
    private int acceptedCount;
    private int invitedCount;
    private int pendingInviteCount;
    private int minimumStartCount;
    private int recommendedReadyCount;
    private Timestamp startedAt;
    private String startedByUserId;
    private boolean peerRemindersEnabled;
    private List<String> reminderOptedInUserIds;
    private boolean recapAvailable;
    private boolean rematchAvailable;
    private String rematchChallengeId;
}
