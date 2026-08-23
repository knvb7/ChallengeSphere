package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.EqualsAndHashCode;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ChallengesByHashtagResponse extends CursorResponse {
    private List<String> hashtags;
    private List<ChallengeWithParticipantCount> challenges;

    @Data
    public static class ChallengeWithParticipantCount {
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
        private int activeParticipantCount;

        public ChallengeWithParticipantCount(Challenge challenge, int activeParticipantCount) {
            this.id = stringId(challenge.getId());
            this.hashtags = challenge.getHashtags();
            this.categories = challenge.getCategories();
            this.title = challenge.getTitle();
            this.description = challenge.getDescription();
            this.benefits = challenge.getBenefits();
            this.isPrivate = challenge.getIsPrivate();
            this.startDate = challenge.getStartDate();
            this.active = challenge.getActive();
            this.createdAt = challenge.getCreatedAt();
            this.creatorId = stringId(challenge.getCreatorId());
            this.creatorUsername = challenge.getCreatorUsername();
            this.creatorProfilePicUrl = challenge.getCreatorProfilePicUrl();
            this.currentOwnerId = stringId(challenge.getCurrentOwnerId());
            this.numberOfDays = challenge.getNumberOfDays();
            this.challengeMode = challenge.getChallengeMode();
            this.proofMode = challenge.getProofMode();
            this.officialTemplateKey = challenge.getOfficialTemplateKey();
            this.cohortKey = challenge.getCohortKey();
            this.coverPhotoUrls = challenge.getCoverPhotoUrls();
            this.activeParticipantCount = activeParticipantCount;
        }
    }

    public static ChallengesByHashtagResponse create(List<String> hashtags, List<ChallengeWithParticipantCount> challenges,
                                                      String nextCursor, boolean hasMore) {
        ChallengesByHashtagResponse response = new ChallengesByHashtagResponse();
        response.setHashtags(hashtags);
        response.setChallenges(challenges);
        response.setNextCursor(nextCursor);
        response.setHasMore(hasMore);
        return response;
    }

    private static String stringId(Object value) {
        return value == null ? null : value.toString();
    }
}
