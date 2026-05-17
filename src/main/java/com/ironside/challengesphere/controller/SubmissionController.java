package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.SubmissionResponse;
import com.ironside.challengesphere.api.response.SubmissionsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
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
    public ResponseEntity<SubmissionResponse> createSubmission(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateSubmissionRequest request) throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal));
        logger.info("Received submission request for user: {}, challenge: {}, day: {}",
                request.getUserId(), request.getChallengeId(), request.getDayNumber());

        SubmissionResponse response = submissionService.createSubmission(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Reupload an existing submission's media and caption without changing its day.
     * PUT /api/v1/submissions/{submissionId}
     */
    @PutMapping("/{submissionId}")
    public ResponseEntity<SubmissionResponse> reuploadSubmission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long submissionId,
            @Valid @RequestBody CreateSubmissionRequest request) throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal));
        logger.info("Received reupload request for submission: {}, user: {}", submissionId, request.getUserId());

        SubmissionResponse response = submissionService.reuploadSubmission(submissionId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Soft delete a submission owned by the authenticated user.
     * DELETE /api/v1/submissions/{submissionId}
     */
    @DeleteMapping("/{submissionId}")
    public ResponseEntity<Void> deleteSubmission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long submissionId) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        logger.info("Received delete request for submission: {}, user: {}", submissionId, userId);

        submissionService.deleteSubmission(submissionId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Generate presigned URLs for media upload (Instagram-style)
     * POST /api/v1/submissions/presigned-urls
     */
    @PostMapping("/presigned-urls")
    public ResponseEntity<PresignedUrlsResponse> generatePresignedUrls(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GeneratePresignedUrlsRequest request) throws ChallengeSphereException {
        request.setUserId(SecurityAccess.requireUserId(principal));
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
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long callerId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {

        logger.info("Getting submissions for user: {}, challenge: {}, page: {}, pageSize: {}", userId, challengeId, page, pageSize);
        SubmissionsResponse response = submissionService.getSubmissionsByUserAndChallenge(userId, challengeId, page, pageSize, SecurityAccess.requireUserId(principal));
        return ResponseEntity.ok(response);
    }

    /**
     * Download an Instagram-ready collage for a user's challenge submissions.
     * GET /api/v1/submissions/user/{userId}/challenge/{challengeId}/collage
     */
    @GetMapping(value = "/user/{userId}/challenge/{challengeId}/collage", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> downloadChallengeCollage(
            @PathVariable Long userId,
            @PathVariable Long challengeId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        SecurityAccess.requireSelf(principal, userId);
        logger.info("Generating challenge collage for user: {}, challenge: {}", userId, challengeId);

        byte[] collage = submissionService.generateChallengeCollage(userId, challengeId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"challenge-collage.jpg\"")
                .body(collage);
    }

    /**
     * Get all active submissions for a challenge (with pagination)
     * GET /api/v1/submissions/challenge/{challengeId}
     */
    @GetMapping("/challenge/{challengeId}")
    public ResponseEntity<SubmissionsResponse> getSubmissionsByChallenge(
            @PathVariable Long challengeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long callerId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {

        logger.info("Getting submissions for challenge: {}, page: {}, pageSize: {}", challengeId, page, pageSize);
        SubmissionsResponse response = submissionService.getSubmissionsByChallenge(challengeId, page, pageSize, SecurityAccess.requireUserId(principal));
        return ResponseEntity.ok(response);
    }

    /**
     * Get random visible submissions for a challenge day.
     * GET /api/v1/submissions/challenge/{challengeId}/global?dayNumber={day}
     */
    @GetMapping("/challenge/{challengeId}/global")
    public ResponseEntity<SubmissionsResponse> getRandomChallengeSubmissionsByDay(
            @PathVariable Long challengeId,
            @RequestParam @Min(1) Integer dayNumber,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {

        Long callerId = SecurityAccess.requireUserId(principal);
        logger.info("Getting random submissions for challenge: {}, day: {}, page: {}, pageSize: {}", challengeId, dayNumber, page, pageSize);
        SubmissionsResponse response = submissionService.getRandomChallengeSubmissionsByDay(challengeId, dayNumber, page, pageSize, callerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all active submissions by a user (with pagination)
     * GET /api/v1/submissions/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<SubmissionsResponse> getSubmissionsByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Long callerId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {

        logger.info("Getting submissions for user: {}, page: {}, pageSize: {}", userId, page, pageSize);
        SubmissionsResponse response = submissionService.getSubmissionsByUser(userId, page, pageSize, SecurityAccess.requireUserId(principal));
        return ResponseEntity.ok(response);
    }

    /**
     * Get a single submission by ID.
     * GET /api/v1/submissions/{submissionId}?callerId={userId}
     * callerId is the requesting user — used to gate access to private submissions.
     * TODO: replace with @AuthenticationPrincipal once SecurityConfig is re-enabled.
     */
    @GetMapping("/{submissionId}")
    public ResponseEntity<Submission> getSubmissionById(
            @PathVariable Long submissionId,
            @RequestParam(required = false) Long callerId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Getting submission by id: {}, callerId: {}", submissionId, actorUserId);
        Submission submission = submissionService.getSubmissionById(submissionId, actorUserId);
        if (submission == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(submission);
    }
}
