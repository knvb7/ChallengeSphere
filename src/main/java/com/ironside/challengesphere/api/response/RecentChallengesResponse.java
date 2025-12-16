package com.ironside.challengesphere.api.response;

import java.util.List;

import com.ironside.challengesphere.model.Challenge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for recent challenges endpoint
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentChallengesResponse {
    
    private List<ChallengeInfo> challenges;
    private int page;
    private int pageSize;
    private int totalCount;
    private int totalPages;
    private boolean hasMore;

    /**
     * Challenge info with engagement metrics
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChallengeInfo {
        private Long id;
        private String title;
        private String description;
        private String hashtags;
        private String categories;
        private String benefits;
        private Integer numberOfDays;
        private String startDate;
        private Long creatorId;
        private String creatorUsername;
        private Boolean isPrivate;
        private Boolean active;
        private String createdAt;
        
        // Engagement metrics from Redis
        private long viewCount;
        private long engagementScore;
        private int participantCount;
        
        public static ChallengeInfo fromChallenge(Challenge challenge) {
            return ChallengeInfo.builder()
                    .id(challenge.getId())
                    .title(challenge.getTitle())
                    .description(challenge.getDescription())
                    .hashtags(challenge.getHashtags())
                    .categories(challenge.getCategories())
                    .benefits(challenge.getBenefits())
                    .numberOfDays(challenge.getNumberOfDays())
                    .startDate(challenge.getStartDate() != null ? challenge.getStartDate().toString() : null)
                    .creatorId(challenge.getCreatorId())
                    .isPrivate(challenge.getIsPrivate())
                    .active(challenge.getActive())
                    .createdAt(challenge.getCreatedAt() != null ? challenge.getCreatedAt().toString() : null)
                    .viewCount(0)
                    .engagementScore(0)
                    .participantCount(0)
                    .build();
        }
        
        public static ChallengeInfo fromChallenge(Challenge challenge, long viewCount, long engagementScore, int participantCount) {
            ChallengeInfo info = fromChallenge(challenge);
            info.setViewCount(viewCount);
            info.setEngagementScore(engagementScore);
            info.setParticipantCount(participantCount);
            return info;
        }
    }

    /**
     * Create a RecentChallengesResponse from a list of challenges
     */
    public static RecentChallengesResponse create(List<ChallengeInfo> challenges, int page, int pageSize, int totalCount) {
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        boolean hasMore = page < totalPages;
        
        return RecentChallengesResponse.builder()
                .challenges(challenges)
                .page(page)
                .pageSize(pageSize)
                .totalCount(totalCount)
                .totalPages(totalPages)
                .hasMore(hasMore)
                .build();
    }

    /**
     * Create an empty response
     */
    public static RecentChallengesResponse empty(int page, int pageSize) {
        return RecentChallengesResponse.builder()
                .challenges(List.of())
                .page(page)
                .pageSize(pageSize)
                .totalCount(0)
                .totalPages(0)
                .hasMore(false)
                .build();
    }
}

