package com.ironside.challengesphere.service;

import org.springframework.stereotype.Service;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Submission;

@Service
public interface SubmissionService {
    SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUserAndChallenge(Long userId, Long challengeId, int page, int pageSize) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByChallenge(Long challengeId, int page, int pageSize) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUser(Long userId, int page, int pageSize) throws ChallengeSphereException;
    PresignedUrlsResponse generatePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException;
    
    /**
     * Get a single submission by ID with caching
     * @param submissionId the submission ID
     * @return the submission or null if not found
     */
    Submission getSubmissionById(Long submissionId);
}