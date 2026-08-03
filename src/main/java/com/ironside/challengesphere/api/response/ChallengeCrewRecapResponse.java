package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeCrewRecapResponse {
    private Long challengeId;
    private String challengeTitle;
    private int participantCount;
    private int contributorCount;
    private int checkInCount;
    private int totalPossibleCheckIns;
    private int collectiveCompletionPercent;
    private Timestamp recapReadyAt;
}
