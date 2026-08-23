package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class ChallengeProgressResponse {
    private String challengeId;
    private String challengeTitle;
    private Integer totalDays;
    private int daysCompleted;
    private int completionPercentage;
    private int currentStreak;
    private int longestStreak;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ChallengeMomentumResponse momentum;
    private List<DayProgress> days;

    @Data
    public static class DayProgress {
        private int dayNumber;
        private String submissionId;
        private String thumbnailUrl;
        private long gloriesCount;
        private Timestamp submittedAt;

        public static DayProgress submitted(int dayNumber, String submissionId, String thumbnailUrl,
                                            long gloriesCount, Timestamp submittedAt) {
            DayProgress dp = new DayProgress();
            dp.dayNumber = dayNumber;
            dp.submissionId = submissionId;
            dp.thumbnailUrl = thumbnailUrl;
            dp.gloriesCount = gloriesCount;
            dp.submittedAt = submittedAt;
            return dp;
        }

        public static DayProgress empty(int dayNumber) {
            DayProgress dp = new DayProgress();
            dp.dayNumber = dayNumber;
            return dp;
        }
    }
}
