package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.Data;

import java.util.List;

@Data
public class PopularChallengesResponse {
    private List<ChallengeWithParticipantCount> challenges;
    private int currentPage;
    private int pageSize;
    private int totalCount;
    private int totalPages;

    @Data
    public static class ChallengeWithParticipantCount {
        private Challenge challenge;
        private int participantCount;

        public ChallengeWithParticipantCount(Challenge challenge, int participantCount) {
            this.challenge = challenge;
            this.participantCount = participantCount;
        }
    }

    public static PopularChallengesResponse create(List<ChallengeWithParticipantCount> challenges,
                                                    int page, int pageSize, int totalCount) {
        PopularChallengesResponse response = new PopularChallengesResponse();
        response.setChallenges(challenges);
        response.setCurrentPage(page);
        response.setPageSize(pageSize);
        response.setTotalCount(totalCount);
        response.setTotalPages((int) Math.ceil((double) totalCount / pageSize));
        return response;
    }
}

