package com.ironside.challengesphere.api.response;

import java.util.List;
import java.sql.Timestamp;
import java.time.LocalDate;

import com.ironside.challengesphere.model.Challenge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for recent challenges endpoint
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RecentChallengesResponse extends CursorResponse {
    
    private List<ChallengeInfo> challenges;

    /**
     * Challenge info with engagement metrics
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChallengeInfo {
        private String id;
        private String hashtags;
        private String categories;
        private String title;
        private String description;
        private String benefits;
        private Boolean isPrivate;
        private LocalDate startDate;
        private Boolean active;
        private Timestamp createdAt;
        private String creatorId;
        private String creatorUsername;
        private String creatorProfilePicUrl;
        private String currentOwnerId;
        private Integer numberOfDays;
        private String challengeMode;
        private String proofMode;
        private String officialTemplateKey;
        private String cohortKey;
        private String coverPhotoUrls;
        private long viewCount;
        private long engagementScore;
        private int participantCount;

        public static ChallengeInfo fromChallenge(Challenge challenge) {
            return ChallengeInfo.builder()
                    .id(stringId(challenge.getId()))
                    .hashtags(challenge.getHashtags())
                    .categories(challenge.getCategories())
                    .title(challenge.getTitle())
                    .description(challenge.getDescription())
                    .benefits(challenge.getBenefits())
                    .isPrivate(challenge.getIsPrivate())
                    .startDate(challenge.getStartDate())
                    .active(challenge.getActive())
                    .createdAt(challenge.getCreatedAt())
                    .creatorId(stringId(challenge.getCreatorId()))
                    .creatorUsername(challenge.getCreatorUsername())
                    .creatorProfilePicUrl(challenge.getCreatorProfilePicUrl())
                    .currentOwnerId(stringId(challenge.getCurrentOwnerId()))
                    .numberOfDays(challenge.getNumberOfDays())
                    .challengeMode(challenge.getChallengeMode())
                    .proofMode(challenge.getProofMode())
                    .officialTemplateKey(challenge.getOfficialTemplateKey())
                    .cohortKey(challenge.getCohortKey())
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
    public static RecentChallengesResponse create(
            List<ChallengeInfo> challenges,
            String nextCursor,
            boolean hasMore
    ) {
        RecentChallengesResponse response = RecentChallengesResponse.builder()
                .challenges(challenges)
                .build();
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }

    /**
     * Create an empty response
     */
    public static RecentChallengesResponse empty() {
        RecentChallengesResponse response = RecentChallengesResponse.builder()
                .challenges(List.of())
                .build();
        response.setHasMore(false);
        return response;
    }

    private static String stringId(Object value) {
        return value == null ? null : value.toString();
    }
}
