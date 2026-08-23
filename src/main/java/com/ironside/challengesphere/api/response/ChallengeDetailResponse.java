package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Challenge;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;

@Data
public class ChallengeDetailResponse {
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
    private int participantCount;
    private boolean isJoined;

    public static ChallengeDetailResponse from(Challenge challenge, int participantCount, boolean isJoined) {
        ChallengeDetailResponse r = new ChallengeDetailResponse();
        r.id = stringId(challenge.getId());
        r.hashtags = challenge.getHashtags();
        r.categories = challenge.getCategories();
        r.title = challenge.getTitle();
        r.description = challenge.getDescription();
        r.benefits = challenge.getBenefits();
        r.isPrivate = challenge.getIsPrivate();
        r.startDate = challenge.getStartDate();
        r.active = challenge.getActive();
        r.createdAt = challenge.getCreatedAt();
        r.creatorId = stringId(challenge.getCreatorId());
        r.creatorUsername = challenge.getCreatorUsername();
        r.creatorProfilePicUrl = challenge.getCreatorProfilePicUrl();
        r.currentOwnerId = stringId(challenge.getCurrentOwnerId());
        r.numberOfDays = challenge.getNumberOfDays();
        r.challengeMode = challenge.getChallengeMode();
        r.proofMode = challenge.getProofMode();
        r.officialTemplateKey = challenge.getOfficialTemplateKey();
        r.cohortKey = challenge.getCohortKey();
        r.coverPhotoUrls = challenge.getCoverPhotoUrls();
        r.participantCount = participantCount;
        r.isJoined = isJoined;
        return r;
    }

    private static String stringId(Object value) {
        return value == null ? null : value.toString();
    }
}
