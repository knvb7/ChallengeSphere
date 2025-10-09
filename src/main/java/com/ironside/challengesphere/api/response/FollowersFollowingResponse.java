package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowersFollowingResponse {

    private Long userId;
    private List<User> users;
    private Integer currentPage;
    private Integer pageSize;
    private Integer totalCount;
    private Integer totalPages;
    private String type; // "followers" or "following"
    private Boolean hasNext;
    private Boolean hasPrevious;

    public static FollowersFollowingResponse followers(Long userId, List<User> followers, int currentPage, int pageSize, int totalCount) {
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        return new FollowersFollowingResponse(
            userId,
            followers,
            currentPage,
            pageSize,
            totalCount,
            totalPages,
            "followers",
            currentPage < totalPages,
            currentPage > 1
        );
    }

    public static FollowersFollowingResponse following(Long userId, List<User> following, int currentPage, int pageSize, int totalCount) {
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        return new FollowersFollowingResponse(
            userId,
            following,
            currentPage,
            pageSize,
            totalCount,
            totalPages,
            "following",
            currentPage < totalPages,
            currentPage > 1
        );
    }
}
