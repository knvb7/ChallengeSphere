package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Submission;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class SubmissionResponse {
    private boolean success;
    private String message;
    private Long submissionId;
    private Long userId;
    private Long challengeId;
    private Integer dayNumber;
    private String description;
    private List<String> mediaUrls;
    private String proofPayload;
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
        response.setSubmissionId(submission.getId());
        response.setUserId(submission.getUserId());
        response.setChallengeId(submission.getChallengeId());
        response.setDayNumber(submission.getDayNumber());
        response.setDescription(submission.getDescription());
        response.setMediaUrls(mediaUrls);
        response.setProofPayload(submission.getProofPayload());
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
}
