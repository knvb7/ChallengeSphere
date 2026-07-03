package com.ironside.challengesphere.api.response;

import java.util.List;
import java.sql.Timestamp;
import java.time.LocalDate;

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
        private String hashtags;
        private String categories;
        private String title;
        private String description;
        private String benefits;
        private Boolean isPrivate;
        private LocalDate startDate;
        private Boolean active;
        private Timestamp createdAt;
        private Long creatorId;
        private String creatorUsername;
        private String creatorProfilePicUrl;
        private Long currentOwnerId;
        private Integer numberOfDays;
        private String challengeMode;
        private String coverPhotoUrls;
        private long viewCount;
        private long engagementScore;
        private int participantCount;

        public static ChallengeInfo fromChallenge(Challenge challenge) {
            return ChallengeInfo.builder()
                    .id(challenge.getId())
                    .hashtags(challenge.getHashtags())
                    .categories(challenge.getCategories())
                    .title(challenge.getTitle())
                    .description(challenge.getDescription())
                    .benefits(challenge.getBenefits())
                    .isPrivate(challenge.getIsPrivate())
                    .startDate(challenge.getStartDate())
                    .active(challenge.getActive())
                    .createdAt(challenge.getCreatedAt())
                    .creatorId(challenge.getCreatorId())
                    .creatorUsername(challenge.getCreatorUsername())
                    .creatorProfilePicUrl(challenge.getCreatorProfilePicUrl())
                    .currentOwnerId(challenge.getCurrentOwnerId())
                    .numberOfDays(challenge.getNumberOfDays())
                    .challengeMode(challenge.getChallengeMode())
                    .coverPhotoUrls(challenge.getCoverPhotoUrls())
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
