package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.CommentResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.CommentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/comments")
public class CommentController {

    private static final Logger logger = LogManager.getLogger(CommentController.class);

    @Autowired
    private CommentService commentService;

    /**
     * Get paginated comments for a submission
     * GET /api/v1/comments/submission/{submissionId}
     */
    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<CursorPageResponse<CommentResponse>> getCommentsForSubmission(
            @PathVariable String submissionId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String callerId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) throws ChallengeSphereException {
        logger.info("Get comments for submission: {}, limit: {}", submissionId, limit);
        return ResponseEntity.ok(commentService.getCommentsForSubmission(
                submissionId,
                SecurityAccess.requireUserId(principal).toString(),
                limit,
                cursor
        ));
    }

    /**
     * Create a new comment on a submission
     * POST /api/v1/comments
     */
    @PostMapping("")
    public ResponseEntity<CommentResponse> createComment(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String submissionId,
            @RequestParam(required = false) String userId,
            @RequestParam @NotBlank @Size(max = 2000) String content) throws ChallengeSphereException {
        String actorUserId = SecurityAccess.requireUserId(principal).toString();
        logger.info("Create comment for submission: {} by user: {}", submissionId, actorUserId);
        CommentResponse comment = commentService.createComment(submissionId, actorUserId, content);
        return ResponseEntity.ok(comment);
    }

    /**
     * Update an existing comment
     * PUT /api/v1/comments/{commentId}
     */
    @PutMapping("/{commentId}")
    public ResponseEntity<Comment> updateComment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String commentId,
            @RequestParam(required = false) String userId,
            @RequestParam @NotBlank @Size(max = 2000) String content) throws ChallengeSphereException {
        String actorUserId = SecurityAccess.requireUserId(principal).toString();
        logger.info("Update comment: {} by user: {}", commentId, actorUserId);
        Comment comment = commentService.updateComment(commentId, actorUserId, content);
        return ResponseEntity.ok(comment);
    }

    /**
     * Delete a comment (soft delete - set active = false)
     * DELETE /api/v1/comments/{commentId}
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String commentId,
            @RequestParam(required = false) String userId) throws ChallengeSphereException {
        String actorUserId = SecurityAccess.requireUserId(principal).toString();
        logger.info("Delete comment: {} by user: {}", commentId, actorUserId);
        commentService.deleteComment(commentId, actorUserId);
        return ResponseEntity.ok().build();
    }
}
