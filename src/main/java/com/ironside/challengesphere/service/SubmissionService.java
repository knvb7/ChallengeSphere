package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.springframework.stereotype.Service;

@Service
public interface SubmissionService {
    SubmissionResponse createSubmission(CreateSubmissionRequest request) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUserAndChallenge(Long userId, Long challengeId, int page, int pageSize) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByChallenge(Long challengeId, int page, int pageSize) throws ChallengeSphereException;
    SubmissionsResponse getSubmissionsByUser(Long userId, int page, int pageSize) throws ChallengeSphereException;
}