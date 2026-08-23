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
public class LeaveChallengeResponse {
    private String challengeId;
    private String challengeTitle;
    private List<String> successfulUserIds;
    private List<String> failedUserIds;
    private String message;
    private boolean success;

    public static LeaveChallengeResponse success(String challengeId, String challengeTitle, List<String> successfulUserIds) {
        return LeaveChallengeResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .successfulUserIds(successfulUserIds)
                .failedUserIds(List.of())
                .message("Successfully removed " + successfulUserIds.size() + " user(s) from challenge")
                .success(true)
                .build();
    }

    public static LeaveChallengeResponse partial(String challengeId, String challengeTitle,
                                                  List<String> successfulUserIds, List<String> failedUserIds) {
        return LeaveChallengeResponse.builder()
                .challengeId(challengeId)
                .challengeTitle(challengeTitle)
                .successfulUserIds(successfulUserIds)
                .failedUserIds(failedUserIds)
                .message("Removed " + successfulUserIds.size() + " user(s), failed for " + failedUserIds.size() + " user(s)")
                .success(true)
                .build();
    }

    public static LeaveChallengeResponse failure(String challengeId, String message) {
        return LeaveChallengeResponse.builder()
                .challengeId(challengeId)
                .successfulUserIds(List.of())
                .failedUserIds(List.of())
                .message(message)
                .success(false)
                .build();
    }
}

