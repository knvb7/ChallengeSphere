package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Data
public class MyChallengesResponse {
    private List<ChallengeSummary> challenges;
    private int totalActive;
    private int totalCompleted;

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
        private Long currentOwnerId;
        private Integer numberOfDays;
        private String coverPhotoUrls;
        private int daysCompleted;
        private int currentStreak;
        private int completionPercentage;
        private Timestamp joinedAt;
        private boolean completed;

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
            summary.currentOwnerId = challenge.getCurrentOwnerId();
            summary.numberOfDays = challenge.getNumberOfDays();
            summary.coverPhotoUrls = challenge.getCoverPhotoUrls();
            return summary;
        }
    }

    public static MyChallengesResponse create(List<ChallengeSummary> challenges) {
        MyChallengesResponse r = new MyChallengesResponse();
        r.challenges = challenges;
        r.totalActive = (int) challenges.stream().filter(c -> !c.isCompleted()).count();
        r.totalCompleted = (int) challenges.stream().filter(ChallengeSummary::isCompleted).count();
        return r;
    }
}
