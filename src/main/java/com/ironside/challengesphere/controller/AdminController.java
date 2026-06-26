package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ModerationFlagRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for moderation workflow.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger logger = LogManager.getLogger(AdminController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModerationFlagRepository moderationFlagRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    /**
     * Reactivate a user that was banned (by moderation or otherwise).
     * POST /api/v1/admin/users/{id}/reactivate
     */
    @PostMapping("/users/{id}/reactivate")
    @Transactional
    public ResponseEntity<Map<String, Object>> reactivateUser(@PathVariable Long id) throws ChallengeSphereException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));
        user.setActive(true);
        user.setBannedAt(null);
        user.setBanReason(null);
        userRepository.save(user);
        logger.info("Reactivated user {}", id);
        return ResponseEntity.ok(Map.of("userId", id, "active", true));
    }

    /**
     * List submissions awaiting human moderation review.
     * GET /api/v1/admin/moderation/pending
     */
    @GetMapping("/moderation/pending")
    public ResponseEntity<List<ModerationFlag>> pendingFlags() {
        return ResponseEntity.ok(moderationFlagRepository.findPending());
    }

    /**
     * Resolve a pending flag. resolution = APPROVED | REMOVED | BANNED_USER.
     * APPROVED: un-hide the submission.
     * REMOVED: leave submission hidden (active=false).
     * BANNED_USER: leave hidden and also ban the owner.
     */
    @PostMapping("/moderation/flags/{flagId}/resolve")
    @Transactional
    public ResponseEntity<Map<String, Object>> resolveFlag(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long flagId,
            @RequestParam String resolution) throws ChallengeSphereException {
        Long reviewerId = SecurityAccess.requireUserId(principal);
        ModerationFlag flag = moderationFlagRepository.findById(flagId)
                .orElseThrow(() -> new ChallengeSphereException("Flag not found", HttpStatus.NOT_FOUND, logger));
        if (flag.getReviewedAt() != null) {
            throw new ChallengeSphereException("Flag already resolved", HttpStatus.CONFLICT, logger);
        }

        String res = resolution == null ? "" : resolution.toUpperCase();
        switch (res) {
            case "APPROVED" -> {
                Submission s = submissionRepository.findById(flag.getSubmissionId()).orElse(null);
                if (s != null) {
                    s.setActive(true);
                    submissionRepository.save(s);
                }
            }
            case "REMOVED" -> { /* submission already hidden — no-op */ }
            case "BANNED_USER" -> {
                User u = userRepository.findById(flag.getUserId()).orElse(null);
                if (u != null) {
                    u.setActive(false);
                    u.setBannedAt(new Timestamp(System.currentTimeMillis()));
                    u.setBanReason("Moderation review: " + flag.getCategory() + " " + flag.getSeverity());
                    userRepository.save(u);
                }
            }
            default -> throw new ChallengeSphereException(
                    "resolution must be APPROVED, REMOVED, or BANNED_USER",
                    HttpStatus.BAD_REQUEST, logger);
        }

        flag.setReviewedAt(new Timestamp(System.currentTimeMillis()));
        flag.setReviewedBy(reviewerId);
        flag.setResolution(res);
        moderationFlagRepository.save(flag);

        return ResponseEntity.ok(Map.of(
                "flagId", flagId,
                "resolution", res
        ));
    }
}
