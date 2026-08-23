package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.User;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FollowersFollowingResponse extends CursorResponse {

    private String userId;
    private List<PublicUserResponse> users;
    private String type; // "followers" or "following"

    public static FollowersFollowingResponse followers(
            String userId,
            List<User> followers,
            String nextCursor,
            boolean hasMore
    ) {
        return create(userId, followers, "followers", nextCursor, hasMore);
    }

    public static FollowersFollowingResponse following(
            String userId,
            List<User> following,
            String nextCursor,
            boolean hasMore
    ) {
        return create(userId, following, "following", nextCursor, hasMore);
    }

    private static FollowersFollowingResponse create(
            String userId,
            List<User> users,
            String type,
            String nextCursor,
            boolean hasMore
    ) {
        FollowersFollowingResponse response = new FollowersFollowingResponse();
        response.setUserId(userId);
        response.setUsers(PublicUserResponse.fromUsers(users));
        response.setType(type);
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }
}
