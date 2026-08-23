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
public class UserSearchResponse extends CursorResponse {
    private String query;
    private List<PublicUserResponse> users;

    public static UserSearchResponse of(
            String query,
            List<User> users,
            String nextCursor,
            boolean hasMore
    ) {
        UserSearchResponse response = new UserSearchResponse();
        response.setQuery(query);
        response.setUsers(PublicUserResponse.fromUsers(users));
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }

    public static UserSearchResponse empty(String query) {
        return of(query, List.of(), null, false);
    }
}
