package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.User;
import lombok.Data;

import java.util.List;

@Data
public class ChallengeParticipantsResponse {
    private Long challengeId;
    private List<PublicUserResponse> participants;
    private int totalParticipants;

    public ChallengeParticipantsResponse(Long challengeId, List<PublicUserResponse> participants, int totalParticipants) {
        this.challengeId = challengeId;
        this.participants = participants;
        this.totalParticipants = totalParticipants;
    }

    public static ChallengeParticipantsResponse create(Long challengeId, List<User> participants) {
        return new ChallengeParticipantsResponse(
                challengeId,
                PublicUserResponse.fromUsers(participants),
                participants.size()
        );
    }
}
