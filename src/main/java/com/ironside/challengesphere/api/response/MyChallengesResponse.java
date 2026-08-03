package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ironside.challengesphere.model.Challenge;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Data
public class MyChallengesResponse {
    private List<ChallengeSummary> challenges;
    private int totalChallenges;
    private int page;
    private int pageSize;
    private int totalPages;

    @Data
    public static class ChallengeSummary {
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
        private String proofMode;
        private String officialTemplateKey;
        private String cohortKey;
        private String coverPhotoUrls;
        private int daysCompleted;
        private int currentStreak;
        private int completionPercentage;
        private Timestamp joinedAt;
        private boolean completed;
        private int participantCount;
        private int activeParticipantCount;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private ChallengeMomentumResponse momentum;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private PrivateRank privateRank;

        public static ChallengeSummary from(Challenge challenge) {
            ChallengeSummary summary = new ChallengeSummary();
            summary.id = challenge.getId();
            summary.hashtags = challenge.getHashtags();
            summary.categories = challenge.getCategories();
            summary.title = challenge.getTitle();
            summary.description = challenge.getDescription();
            summary.benefits = challenge.getBenefits();
            summary.isPrivate = challenge.getIsPrivate();
            summary.startDate = challenge.getStartDate();
            summary.active = challenge.getActive();
            summary.createdAt = challenge.getCreatedAt();
            summary.creatorId = challenge.getCreatorId();
            summary.creatorUsername = challenge.getCreatorUsername();
            summary.creatorProfilePicUrl = challenge.getCreatorProfilePicUrl();
            summary.currentOwnerId = challenge.getCurrentOwnerId();
            summary.numberOfDays = challenge.getNumberOfDays();
            summary.challengeMode = challenge.getChallengeMode();
            summary.proofMode = challenge.getProofMode();
            summary.officialTemplateKey = challenge.getOfficialTemplateKey();
            summary.cohortKey = challenge.getCohortKey();
            summary.coverPhotoUrls = challenge.getCoverPhotoUrls();
            return summary;
        }
    }

    @Data
    public static class PrivateRank {
        private long rank;
        private long totalParticipants;

        public static PrivateRank of(long rank, long totalParticipants) {
            PrivateRank privateRank = new PrivateRank();
            privateRank.rank = rank;
            privateRank.totalParticipants = totalParticipants;
            return privateRank;
        }
    }

    public static MyChallengesResponse create(List<ChallengeSummary> challenges, int page, int pageSize, int totalCount) {
        MyChallengesResponse r = new MyChallengesResponse();
        r.challenges = challenges;
        r.page = page;
        r.pageSize = pageSize;
        r.totalChallenges = totalCount;
        r.totalPages = pageSize > 0 ? (int) Math.ceil((double) totalCount / pageSize) : 0;
        return r;
    }
}
