package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.service.GloryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
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
            @RequestParam Long submissionId,
            @RequestParam Long userId) throws ChallengeSphereException {
        logger.info("Toggle glory request for submission: {} by user: {}", submissionId, userId);
        boolean isGiven = gloryService.toggleGlory(submissionId, userId);
        return ResponseEntity.ok(isGiven);
    }

    /**
     * Get all glories for a submission
     * GET /api/v1/glories/submission/{submissionId}
     */
    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<List<Glory>> getGloriesForSubmission(@PathVariable Long submissionId) throws ChallengeSphereException {
        logger.info("Get glories for submission: {}", submissionId);
        List<Glory> glories = gloryService.getGloriesForSubmission(submissionId);
        return ResponseEntity.ok(glories);
    }

    /**
     * Check if user has given glory to a submission
     * GET /api/v1/glories/check
     */
    @GetMapping("/check")
    public ResponseEntity<Boolean> hasUserGivenGlory(
            @RequestParam Long submissionId,
            @RequestParam Long userId) throws ChallengeSphereException {
        logger.info("Check if user {} has given glory to submission {}", userId, submissionId);
        boolean hasGlory = gloryService.hasUserGivenGlory(submissionId, userId);
        return ResponseEntity.ok(hasGlory);
    }

    /**
     * Get glory count for a submission
     * GET /api/v1/glories/count/{submissionId}
     */
    @GetMapping("/count/{submissionId}")
    public ResponseEntity<Long> getGloryCount(@PathVariable Long submissionId) throws ChallengeSphereException {
        logger.info("Get glory count for submission: {}", submissionId);
        Long count = gloryService.getGloryCount(submissionId);
        return ResponseEntity.ok(count);
    }
}