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
    private String challengeId;
    private String challengeTitle;
    private List<String> successfulUserIds;
    private List<String> failedUserIds;
    private String message;
    private boolean success;

    public static JoinChallengeResponse success(String challengeId, String challengeTitle, List<String> successfulUserIds) {
        return JoinChallengeResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .successfulUserIds(successfulUserIds)
                .failedUserIds(List.of())
                .message("Successfully joined " + successfulUserIds.size() + " user(s) to challenge")
                .success(true)
                .build();
    }

    public static JoinChallengeResponse partial(String challengeId, String challengeTitle,
                                                 List<String> successfulUserIds, List<String> failedUserIds) {
        return JoinChallengeResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .successfulUserIds(successfulUserIds)
                .failedUserIds(failedUserIds)
                .message("Joined " + successfulUserIds.size() + " user(s), failed for " + failedUserIds.size() + " user(s)")
                .success(true)
                .build();
    }

    public static JoinChallengeResponse failure(String challengeId, String message) {
        return JoinChallengeResponse.builder()
                .challengeId(challengeId)
                .successfulUserIds(List.of())
                .failedUserIds(List.of())
                .message(message)
                .success(false)
                .build();
    }
}

