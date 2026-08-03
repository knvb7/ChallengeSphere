package com.ironside.challengesphere.api.response;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

@Data
@NoArgsConstructor
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
    private String challengeMode;
    private String proofMode;
    private List<String> coverPhotoUrls;
    private String message;

    public static ChallengeCreateResponse success(Long challengeId, String title, String description,
                                                  String hashtags, String categories, String benefits,
                                                  Boolean isPrivate, LocalDate startDate, Boolean active,
                                                  Timestamp createdAt, Long creatorId, Long currentOwnerId,
                                                  Integer numberOfDays, String challengeMode, String proofMode,
                                                  String rawCoverPhotoUrls) {
        ChallengeCreateResponse r = new ChallengeCreateResponse();
        r.challengeId = challengeId;
        r.title = title;
        r.description = description;
        r.hashtags = hashtags;
        r.categories = categories;
        r.benefits = benefits;
        r.isPrivate = isPrivate;
        r.startDate = startDate;
        r.active = active;
        r.createdAt = createdAt;
        r.creatorId = creatorId;
        r.currentOwnerId = currentOwnerId;
        r.numberOfDays = numberOfDays;
        r.challengeMode = challengeMode;
        r.proofMode = proofMode;
        r.coverPhotoUrls = splitUrls(rawCoverPhotoUrls);
        r.message = "Challenge created successfully";
        return r;
    }

    private static List<String> splitUrls(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
