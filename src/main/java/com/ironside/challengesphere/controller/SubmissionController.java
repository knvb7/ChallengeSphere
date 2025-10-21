package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.service.SubmissionService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {
    private static final Logger logger = LogManager.getLogger(SubmissionController.class);

    @Autowired
    private SubmissionService submissionService;

    /**
     * Create a new submission for a challenge
     * POST /api/v1/submissions
     */
    @PostMapping
    public ResponseEntity<SubmissionResponse> createSubmission(@Valid @RequestBody CreateSubmissionRequest request) throws ChallengeSphereException {
        logger.info("Received submission request for user: {}, challenge: {}, day: {}",
                request.getUserId(), request.getChallengeId(), request.getDayNumber());

        SubmissionResponse response = submissionService.createSubmission(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Generate presigned URLs for media upload (Instagram-style)
     * POST /api/v1/submissions/presigned-urls
     */
    @PostMapping("/presignedUrls")
    public ResponseEntity<PresignedUrlsResponse> generatePresignedUrls(
            @Valid @RequestBody GeneratePresignedUrlsRequest request) throws ChallengeSphereException {
        logger.info("Received request to generate presigned URLs for user: {} with {} files",
                request.getUserId(), request.getMediaFiles().size());

        PresignedUrlsResponse response = submissionService.generatePresignedUrls(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all submissions by a user for a specific challenge (with pagination)
     * GET /api/v1/submissions/user/{userId}/challenge/{challengeId}
     */
    @GetMapping("/user/{userId}/challenge/{challengeId}")
    public ResponseEntity<SubmissionsResponse> getSubmissionsByUserAndChallenge(
            @PathVariable Long userId,
            @PathVariable Long challengeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        logger.info("Getting submissions for user: {}, challenge: {}, page: {}, pageSize: {}", userId, challengeId, page, pageSize);
        SubmissionsResponse response = submissionService.getSubmissionsByUserAndChallenge(userId, challengeId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all active submissions for a challenge (with pagination)
     * GET /api/v1/submissions/challenge/{challengeId}
     */
    @GetMapping("/challenge/{challengeId}")
    public ResponseEntity<SubmissionsResponse> getSubmissionsByChallenge(
            @PathVariable Long challengeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        logger.info("Getting submissions for challenge: {}, page: {}, pageSize: {}", challengeId, page, pageSize);
        SubmissionsResponse response = submissionService.getSubmissionsByChallenge(challengeId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all active submissions by a user (with pagination)
     * GET /api/v1/submissions/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<SubmissionsResponse> getSubmissionsByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws ChallengeSphereException {

        logger.info("Getting submissions for user: {}, page: {}, pageSize: {}", userId, page, pageSize);
        SubmissionsResponse response = submissionService.getSubmissionsByUser(userId, page, pageSize);
        return ResponseEntity.ok(response);
    }
}
