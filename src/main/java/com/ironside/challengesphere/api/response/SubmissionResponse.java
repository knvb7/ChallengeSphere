package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Submission;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class SubmissionResponse {
    private boolean success;
    private String message;
    private String submissionId;
    private String userId;
    private String challengeId;
    private Integer dayNumber;
    private String description;
    private List<String> mediaUrls;
    private Boolean isPrivate;
    private Timestamp submissionTime;
    private Long gloriesCount;
    private Long commentsCount;
    private Integer version;
    private Boolean hasGivenGlory;

    public static SubmissionResponse success(Submission submission, List<String> mediaUrls) {
        SubmissionResponse response = new SubmissionResponse();
        response.setSuccess(true);
        response.setMessage("Submission created successfully");
        response.setSubmissionId(stringId(submission.getId()));
        response.setUserId(stringId(submission.getUserId()));
        response.setChallengeId(stringId(submission.getChallengeId()));
        response.setDayNumber(submission.getDayNumber());
        response.setDescription(submission.getDescription());
        response.setMediaUrls(mediaUrls);
        response.setIsPrivate(submission.getIsPrivate());
        response.setSubmissionTime(submission.getSubmissionTime());
        response.setGloriesCount(submission.getGloriesCount());
        response.setCommentsCount(submission.getCommentsCount());
        response.setVersion(submission.getVersion());
        return response;
    }

    public static SubmissionResponse success(Submission submission, List<String> mediaUrls, Boolean hasGivenGlory) {
        SubmissionResponse response = success(submission, mediaUrls);
        response.setHasGivenGlory(hasGivenGlory);
        return response;
    }

    public static SubmissionResponse failure(String message) {
        SubmissionResponse response = new SubmissionResponse();
        response.setSuccess(false);
        response.setMessage(message);
        return response;
    }

    private static String stringId(Object value) {
        return value == null ? null : value.toString();
    }
}
