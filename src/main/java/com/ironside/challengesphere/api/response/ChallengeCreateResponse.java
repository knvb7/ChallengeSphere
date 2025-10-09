package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.sql.Timestamp;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeCreateResponse {

    private Long challengeId;
    private String title;
    private String description;
    private String hashtags;
    private String categories;
    private String benefits;
    private Boolean isPrivate;
    private LocalDate startDate;
    private Boolean active;
    private Timestamp createdAt;
    private Long creatorId;
    private Long currentOwnerId;
    private Integer numberOfDays;
    private String message;

    public static ChallengeCreateResponse success(Long challengeId, String title, String description,
                                                 String hashtags, String categories, String benefits,
                                                 Boolean isPrivate, LocalDate startDate, Boolean active,
                                                 Timestamp createdAt, Long creatorId, Long currentOwnerId,
                                                 Integer numberOfDays) {
        return new ChallengeCreateResponse(challengeId, title, description, hashtags, categories, benefits,
                                         isPrivate, startDate, active, createdAt, creatorId, currentOwnerId,
                                         numberOfDays, "Challenge created successfully");
    }
}
