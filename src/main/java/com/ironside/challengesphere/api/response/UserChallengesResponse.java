package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserChallengesResponse extends CursorResponse {

    private String userId;
    private List<Challenge> challenges;

    public static UserChallengesResponse create(
            String userId,
            List<Challenge> challenges,
            String nextCursor,
            boolean hasMore
    ) {
        UserChallengesResponse response = new UserChallengesResponse();
        response.setUserId(userId);
        response.setChallenges(challenges);
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }
}
