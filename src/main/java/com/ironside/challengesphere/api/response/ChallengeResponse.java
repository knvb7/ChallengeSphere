package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.util.ChallengeStatus;
import com.ironside.challengesphere.util.ChallengeType;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class ChallengeResponse {
    private Long id;
    private String title;
    private String description;
    private String rules;
    private LocalDate startDate;
    private LocalDate endDate;
    private ChallengeType challengeType;
    private ChallengeStatus status;
    private String inviteCode;
    private OffsetDateTime createdAt;
    private UserProfileResponse creator;
    private int participantCount;
    private boolean currentUserParticipating;
}
