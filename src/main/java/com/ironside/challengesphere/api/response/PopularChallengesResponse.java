package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
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
        private int participantCount;

        public ChallengeWithParticipantCount(Challenge challenge, int participantCount) {
            this.id = challenge.getId();
            this.hashtags = challenge.getHashtags();
            this.categories = challenge.getCategories();
            this.title = challenge.getTitle();
            this.description = challenge.getDescription();
            this.benefits = challenge.getBenefits();
            this.isPrivate = challenge.getIsPrivate();
            this.startDate = challenge.getStartDate();
            this.active = challenge.getActive();
            this.createdAt = challenge.getCreatedAt();
            this.creatorId = challenge.getCreatorId();
            this.currentOwnerId = challenge.getCurrentOwnerId();
            this.numberOfDays = challenge.getNumberOfDays();
            this.coverPhotoUrls = challenge.getCoverPhotoUrls();
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
