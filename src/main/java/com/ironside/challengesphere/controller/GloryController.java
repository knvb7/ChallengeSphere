package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.GloryCheckResponse;
import com.ironside.challengesphere.api.response.GloryUserResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.GloryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/glories")
public class GloryController {
    private static final Logger logger = LogManager.getLogger(GloryController.class);

    @Autowired
    private GloryService gloryService;

    /**
     * Toggle glory (give or remove) for a submission
     * POST /api/v1/glories/toggle
     */
    @PostMapping("/toggle")
    public ResponseEntity<Boolean> toggleGlory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long submissionId,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Toggle glory request for submission: {} by user: {}", submissionId, actorUserId);
        boolean isGiven = gloryService.toggleGlory(submissionId, actorUserId);
        return ResponseEntity.ok(isGiven);
    }

    /**
     * Get a paginated list of glories for a submission.
     * GET /api/v1/glories/submission/{submissionId}?page=0&size=50
     * A viral submission can accumulate hundreds of thousands of glories — returning
     * the full list in one response would blow up both the server and the client.
     */
    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<Page<GloryUserResponse>> getGloriesForSubmission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long submissionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) throws ChallengeSphereException {
        logger.info("Get glories for submission: {}, page: {}, size: {}", submissionId, page, size);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(gloryService.getGloriesForSubmission(submissionId, pageable, SecurityAccess.requireUserId(principal)));
    }

    /**
     * Check glory + unglory state for a user on a submission
     * GET /api/v1/glories/check?submissionId=X&userId=Y
     */
    @GetMapping("/check")
    public ResponseEntity<GloryCheckResponse> checkGloryStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long submissionId,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Check glory status for user {} on submission {}", actorUserId, submissionId);
        boolean hasGlory = gloryService.hasUserGivenGlory(submissionId, actorUserId);
        boolean hasUngloried = gloryService.hasUserUngloried(submissionId, actorUserId);
        return ResponseEntity.ok(GloryCheckResponse.of(submissionId, actorUserId, hasGlory, hasUngloried));
    }

    /**
     * Toggle unglory (not genuine) for a submission — Button 2
     * POST /api/v1/glories/toggle-unglory
     */
    @PostMapping("/unglory/toggle")
    public ResponseEntity<GloryCheckResponse> toggleUnglory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long submissionId,
            @RequestParam(required = false) Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        logger.info("Toggle unglory request for submission: {} by user: {}", submissionId, actorUserId);
        boolean isUngloried = gloryService.toggleUnglory(submissionId, actorUserId);
        boolean hasGlory = gloryService.hasUserGivenGlory(submissionId, actorUserId);
        return ResponseEntity.ok(GloryCheckResponse.of(submissionId, actorUserId, hasGlory, isUngloried));
    }
    /**
     * Get glory count for a submission
     * GET /api/v1/glories/count/{submissionId}
     */
    @GetMapping("/count/{submissionId}")
    public ResponseEntity<Long> getGloryCount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long submissionId) throws ChallengeSphereException {
        logger.info("Get glory count for submission: {}", submissionId);
        Long count = gloryService.getGloryCount(submissionId, SecurityAccess.requireUserId(principal));
        return ResponseEntity.ok(count);
    }
}
