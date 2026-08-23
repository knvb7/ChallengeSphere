package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.User;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ChallengeParticipantsResponse extends CursorResponse {
    private String challengeId;
    private List<PublicUserResponse> participants;
    private int totalParticipants;

    public ChallengeParticipantsResponse(
            String challengeId,
            List<PublicUserResponse> participants,
            int totalParticipants,
            String nextCursor,
            boolean hasMore
    ) {
        this.challengeId = challengeId;
        this.participants = participants;
        this.totalParticipants = totalParticipants;
        setNextCursor(nextCursor);
        setHasMore(hasMore);
    }

    public static ChallengeParticipantsResponse create(
            String challengeId,
            List<User> participants,
            int totalParticipants,
            String nextCursor,
            boolean hasMore
    ) {
        return new ChallengeParticipantsResponse(
                challengeId,
                PublicUserResponse.fromUsers(participants),
                totalParticipants,
                nextCursor,
                hasMore
        );
    }
}
