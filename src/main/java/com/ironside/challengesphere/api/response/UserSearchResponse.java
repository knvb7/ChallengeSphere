package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchResponse {
    private String query;
    private List<User> users;
    private Integer currentPage;
    private Integer pageSize;
    private Integer totalCount;
    private Integer totalPages;
    private Boolean hasMore;
    private Boolean hasPrevious;

    public static UserSearchResponse of(String query, List<User> users, int currentPage, int pageSize, long totalCount) {
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        return new UserSearchResponse(
                query,
                users,
                currentPage,
                pageSize,
                Math.toIntExact(totalCount),
                totalPages,
                currentPage < totalPages,
                currentPage > 1
        );
    }

    public static UserSearchResponse empty(String query, int currentPage, int pageSize) {
        return of(query, List.of(), currentPage, pageSize, 0);
    }
}
