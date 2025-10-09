package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserChallengesResponse {

    private Long userId;
    private List<Challenge> challenges;
    private Integer currentPage;
    private Integer pageSize;
    private Integer totalCount;
    private Integer totalPages;
    private Boolean hasNext;
    private Boolean hasPrevious;

    public static UserChallengesResponse create(Long userId, List<Challenge> challenges, int currentPage, int pageSize, int totalCount) {
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        return new UserChallengesResponse(
            userId,
            challenges,
            currentPage,
            pageSize,
            totalCount,
            totalPages,
            currentPage < totalPages,
            currentPage > 1
        );
    }
}
