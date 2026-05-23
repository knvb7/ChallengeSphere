package com.ironside.challengesphere.service;

import org.springframework.stereotype.Service;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.util.MediaTypes;
import org.springframework.web.multipart.MultipartFile;

@Service
public interface SubmissionService {
    SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException;
    SubmissionResponse reuploadSubmission(Long submissionId, CreateSubmissionRequest request) throws ChallengeSphereException;
    void deleteSubmission(Long submissionId, Long userId) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUserAndChallenge(Long userId, Long challengeId, int page, int pageSize, Long callerId) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByChallenge(Long challengeId, int page, int pageSize, Long callerId) throws ChallengeSphereException;
    SubmissionsResponse getRandomChallengeSubmissionsByDay(Long challengeId, Integer dayNumber, int page, int pageSize, Long callerId) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUser(Long userId, int page, int pageSize, Long callerId) throws ChallengeSphereException;
    PresignedUrlsResponse generatePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException;
    String uploadSubmissionMedia(
            Long userId,
            MultipartFile file,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType) throws ChallengeSphereException;
    
    /**
     * Get a single submission by ID.
     * @param submissionId the submission ID
     * @param callerId the id of the requesting user — required to authorize access to private submissions
     * @return the submission or null if not found
     */
    Submission getSubmissionById(Long submissionId, Long callerId) throws ChallengeSphereException;
}
