package com.ironside.challengesphere.api.response;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import com.ironside.challengesphere.model.Submission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response representing a single feed item (submission)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedItemResponse {

    private Long submissionId;
    private Long userId;
    private String username;
    private String userProfilePicUrl;
    private Long challengeId;
    private String challengeTitle;
    private Integer dayNumber;
    private String description;
    private List<String> mediaUrls;
    private Long gloriesCount;
    private Long commentsCount;
    private Timestamp createdAt;
    private Boolean isGloriedByCurrentUser;
    private Boolean isUngloriedByCurrentUser;

    /**
     * Create FeedItemResponse from Submission with additional user and challenge info
     */
    public static FeedItemResponse fromSubmission(
            Submission submission,
            String username,
            String userProfilePicUrl,
            String challengeTitle,
            Boolean isGloriedByCurrentUser,
            Boolean isUngloriedByCurrentUser) {
        
        List<String> mediaUrlsList = null;
        if (submission.getMediaUrls() != null && !submission.getMediaUrls().isEmpty()) {
            mediaUrlsList = Arrays.asList(submission.getMediaUrls().split(","));
        }

        return FeedItemResponse.builder()
                .submissionId(submission.getId())
                .userId(submission.getUserId())
                .username(username)
                .userProfilePicUrl(userProfilePicUrl)
                .challengeId(submission.getChallengeId())
                .challengeTitle(challengeTitle)
                .dayNumber(submission.getDayNumber())
                .description(submission.getDescription())
                .mediaUrls(mediaUrlsList)
                .gloriesCount(submission.getGloriesCount())
                .commentsCount(submission.getCommentsCount())
                .createdAt(submission.getCreatedAt())
                .isGloriedByCurrentUser(isGloriedByCurrentUser)
                .isUngloriedByCurrentUser(isUngloriedByCurrentUser)
                .build();
    }
}
