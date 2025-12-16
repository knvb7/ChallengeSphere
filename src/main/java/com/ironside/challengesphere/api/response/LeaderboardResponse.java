package com.ironside.challengesphere.api.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for leaderboard data
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LeaderboardResponse {
    
    private Long challengeId;
    private String challengeTitle;
    private String leaderboardType;
    private List<LeaderboardEntryDTO> entries;
    private Integer totalParticipants;
    private UserRankDTO currentUserRank;
    private Long lastUpdated;
    private Boolean success;
    private String message;
    
    /**
     * Individual leaderboard entry
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LeaderboardEntryDTO {
        private Long userId;
        private String username;
        private String displayName;
        private String profilePictureUrl;
        private Long rank;
        private Double score;
        private Integer submissionCount;
        private Long gloriesCount;
        private Integer currentStreak;
    }
    
    /**
     * Current user's rank information
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserRankDTO {
        private Long userId;
        private Long rank;
        private Double score;
        private Long totalParticipants;
        private Double percentile;
    }
    
    // Factory methods
    public static LeaderboardResponse success(Long challengeId, String challengeTitle, 
            String leaderboardType, List<LeaderboardEntryDTO> entries, Integer totalParticipants) {
        return LeaderboardResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .leaderboardType(leaderboardType)
                .entries(entries)
                .totalParticipants(totalParticipants)
                .lastUpdated(System.currentTimeMillis())
                .success(true)
                .message("Leaderboard retrieved successfully")
                .build();
    }
    
    public static LeaderboardResponse withUserRank(Long challengeId, String challengeTitle, 
            String leaderboardType, List<LeaderboardEntryDTO> entries, 
            Integer totalParticipants, UserRankDTO userRank) {
        return LeaderboardResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .leaderboardType(leaderboardType)
                .entries(entries)
                .totalParticipants(totalParticipants)
                .currentUserRank(userRank)
                .lastUpdated(System.currentTimeMillis())
                .success(true)
                .message("Leaderboard retrieved successfully")
                .build();
    }
    
    public static LeaderboardResponse error(String message) {
        return LeaderboardResponse.builder()
                .success(false)
                .message(message)
                .lastUpdated(System.currentTimeMillis())
                .build();
    }
    
    public static LeaderboardResponse notFound(Long challengeId) {
        return LeaderboardResponse.builder()
                .challengeId(challengeId)
                .success(false)
                .message("Challenge not found with id: " + challengeId)
                .lastUpdated(System.currentTimeMillis())
                .build();
    }
}
