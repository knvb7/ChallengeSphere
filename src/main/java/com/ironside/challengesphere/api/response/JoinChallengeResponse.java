package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinChallengeResponse {
    private Long challengeId;
    private String challengeTitle;
    private List<Long> successfulUserIds;
    private List<Long> failedUserIds;
    private String message;
    private boolean success;

    public static JoinChallengeResponse success(Long challengeId, String challengeTitle, List<Long> successfulUserIds) {
        return JoinChallengeResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .successfulUserIds(successfulUserIds)
                .failedUserIds(List.of())
                .message("Successfully joined " + successfulUserIds.size() + " user(s) to challenge")
                .success(true)
                .build();
    }

    public static JoinChallengeResponse partial(Long challengeId, String challengeTitle,
                                                 List<Long> successfulUserIds, List<Long> failedUserIds) {
        return JoinChallengeResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .successfulUserIds(successfulUserIds)
                .failedUserIds(failedUserIds)
                .message("Joined " + successfulUserIds.size() + " user(s), failed for " + failedUserIds.size() + " user(s)")
                .success(true)
                .build();
    }

    public static JoinChallengeResponse failure(Long challengeId, String message) {
        return JoinChallengeResponse.builder()
                .challengeId(challengeId)
                .successfulUserIds(List.of())
                .failedUserIds(List.of())
                .message(message)
                .success(false)
                .build();
    }
}

