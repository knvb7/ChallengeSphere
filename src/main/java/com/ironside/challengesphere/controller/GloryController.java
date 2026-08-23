package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.GloryCheckResponse;
import com.ironside.challengesphere.api.response.GloryUserResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.GloryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
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
            @RequestParam String submissionId,
            @RequestParam(required = false) String userId) throws ChallengeSphereException {
        String actorUserId = SecurityAccess.requireUserId(principal).toString();
        logger.info("Toggle glory request for submission: {} by user: {}", submissionId, actorUserId);
        boolean isGiven = gloryService.toggleGlory(submissionId, actorUserId);
        return ResponseEntity.ok(isGiven);
    }

    /**
     * Get a paginated list of glories for a submission.
     * GET /api/v1/glories/submission/{submissionId}?limit=50&cursor=...
     * A viral submission can accumulate hundreds of thousands of glories — returning
     * the full list in one response would blow up both the server and the client.
     */
    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<CursorPageResponse<GloryUserResponse>> getGloriesForSubmission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String submissionId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) throws ChallengeSphereException {
        logger.info("Get glories for submission: {}, limit: {}", submissionId, limit);
        return ResponseEntity.ok(gloryService.getGloriesForSubmission(
                submissionId,
                SecurityAccess.requireUserId(principal).toString(),
                limit,
                cursor
        ));
    }

    /**
     * Check glory + unglory state for a user on a submission
     * GET /api/v1/glories/check?submissionId=X&userId=Y
     */
    @GetMapping("/check")
    public ResponseEntity<GloryCheckResponse> checkGloryStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String submissionId,
            @RequestParam(required = false) String userId) throws ChallengeSphereException {
        String actorUserId = SecurityAccess.requireUserId(principal).toString();
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
            @RequestParam String submissionId,
            @RequestParam(required = false) String userId) throws ChallengeSphereException {
        String actorUserId = SecurityAccess.requireUserId(principal).toString();
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
            @PathVariable String submissionId) throws ChallengeSphereException {
        logger.info("Get glory count for submission: {}", submissionId);
        Long count = gloryService.getGloryCount(submissionId, SecurityAccess.requireUserId(principal).toString());
        return ResponseEntity.ok(count);
    }
}
