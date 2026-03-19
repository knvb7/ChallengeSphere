package com.ironside.challengesphere.controller;

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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/comments")
public class CommentController {

    private static final Logger logger = LogManager.getLogger(CommentController.class);

    @Autowired
    private CommentService commentService;

    /**
     * Get paginated comments for a post
     * GET /api/v1/comments/post/{postId}
     */
    @GetMapping("/post/{postId}")
    public ResponseEntity<Page<Comment>> getCommentsForPost(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) throws ChallengeSphereException {
        logger.info("Get comments for post: {}, page: {}, size: {}", postId, page, size);
        Pageable pageable = PageRequest.of(page, size);
        Page<Comment> comments = commentService.getCommentsForPost(postId, pageable);
        return ResponseEntity.ok(comments);
    }

    /**
     * Create a new comment on a post
     * POST /api/v1/comments
     */
    @PostMapping("")
    public ResponseEntity<Comment> createComment(
            @RequestParam UUID postId,
            @RequestParam Long userId,
            @RequestParam String content) throws ChallengeSphereException {
        logger.info("Create comment for post: {} by user: {}", postId, userId);
        Comment comment = commentService.createComment(postId, userId, content);
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