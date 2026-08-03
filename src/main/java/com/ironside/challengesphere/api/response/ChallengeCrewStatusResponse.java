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
    private Long challengeId;
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
    private Long startedByUserId;
    private boolean peerRemindersEnabled;
    private List<Long> reminderOptedInUserIds;
    private boolean recapAvailable;
    private boolean rematchAvailable;
    private Long rematchChallengeId;
}
