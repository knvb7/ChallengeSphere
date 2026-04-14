package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.response.CommentResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.service.CommentService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
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
    public ResponseEntity<Page<CommentResponse>> getCommentsForSubmission(
            @PathVariable Long submissionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) throws ChallengeSphereException {
        logger.info("Get comments for submission: {}, page: {}, size: {}", submissionId, page, size);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(commentService.getCommentsForSubmission(submissionId, pageable));
    }

    /**
     * Create a new comment on a submission
     * POST /api/v1/comments
     */
    @PostMapping("")
    public ResponseEntity<Comment> createComment(
            @RequestParam Long submissionId,
            @RequestParam Long userId,
            @RequestParam String content) throws ChallengeSphereException {
        logger.info("Create comment for submission: {} by user: {}", submissionId, userId);
        Comment comment = commentService.createComment(submissionId, userId, content);
        return ResponseEntity.ok(comment);
    }

    /**
     * Update an existing comment
     * PUT /api/v1/comments/{commentId}
     */
    @PutMapping("/{commentId}")
    public ResponseEntity<Comment> updateComment(
            @PathVariable Long commentId,
            @RequestParam Long userId,
            @RequestParam String content) throws ChallengeSphereException {
        logger.info("Update comment: {} by user: {}", commentId, userId);
        Comment comment = commentService.updateComment(commentId, userId, content);
        return ResponseEntity.ok(comment);
    }

    /**
     * Delete a comment (soft delete - set active = false)
     * DELETE /api/v1/comments/{commentId}
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long commentId,
            @RequestParam Long userId) throws ChallengeSphereException {
        logger.info("Delete comment: {} by user: {}", commentId, userId);
        commentService.deleteComment(commentId, userId);
        return ResponseEntity.ok().build();
    }
}