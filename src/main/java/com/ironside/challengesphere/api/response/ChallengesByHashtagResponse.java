package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.Data;

import java.util.List;

@Data
public class ChallengesByHashtagResponse {
    private List<String> hashtags;
    private List<ChallengeWithParticipantCount> challenges;
    private int currentPage;
    private int pageSize;
    private int totalCount;
    private int totalPages;

    @Data
    public static class ChallengeWithParticipantCount {
        private Challenge challenge;
        private int activeParticipantCount;

        public ChallengeWithParticipantCount(Challenge challenge, int activeParticipantCount) {
            this.challenge = challenge;
            this.activeParticipantCount = activeParticipantCount;
        }
    }

    public static ChallengesByHashtagResponse create(List<String> hashtags, List<ChallengeWithParticipantCount> challenges,
                                                      int page, int pageSize, int totalCount) {
        ChallengesByHashtagResponse response = new ChallengesByHashtagResponse();
        response.setHashtags(hashtags);
        response.setChallenges(challenges);
        response.setCurrentPage(page);
        response.setPageSize(pageSize);
        response.setTotalCount(totalCount);
        response.setTotalPages((int) Math.ceil((double) totalCount / pageSize));
        return response;
    }
}
