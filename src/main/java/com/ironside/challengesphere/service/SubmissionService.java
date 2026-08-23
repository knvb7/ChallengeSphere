package com.ironside.challengesphere.service;

import org.springframework.stereotype.Service;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.ChallengeSubmissionPreviewResponse;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.util.MediaTypes;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public interface SubmissionService {
    SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException;
    SubmissionResponse reuploadSubmission(String submissionId, CreateSubmissionRequest request) throws ChallengeSphereException;
    void deleteSubmission(String submissionId, String userId) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUserAndChallenge(String userId, String challengeId, String callerId, int limit, String cursor) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByChallenge(String challengeId, String callerId, int limit, String cursor, String sortBy, String participantQuery) throws ChallengeSphereException;
    List<ChallengeSubmissionPreviewResponse> getSubmissionPreviews(
            List<String> challengeIds,
            String userId,
            String callerId
    ) throws ChallengeSphereException;
    SubmissionsResponse getRandomChallengeSubmissionsByDay(String challengeId, Integer dayNumber, String callerId, int limit, String cursor, String sortBy, String participantQuery) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUser(String userId, String callerId, int limit, String cursor) throws ChallengeSphereException;
    PresignedUrlsResponse generatePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException;
    String uploadSubmissionMedia(
            String userId,
            MultipartFile file,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType) throws ChallengeSphereException;
    
    /**
     * Get a single submission by ID.
     * @param submissionId the submission ID
     * @param callerId the id of the requesting user — required to authorize access to private submissions
     * @return the submission or null if not found
     */
    Submission getSubmissionById(String submissionId, String callerId) throws ChallengeSphereException;
}
