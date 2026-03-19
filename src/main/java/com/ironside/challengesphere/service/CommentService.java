package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CommentService {
    
    /**
     * Get paginated comments for a post
     */
    Page<Comment> getCommentsForPost(UUID postId, Pageable pageable) throws ChallengeSphereException;

    /**
     * Create a new comment on a post
     */
    Comment createComment(UUID postId, Long userId, String content) throws ChallengeSphereException;

    /**
     * Edit an existing comment
     */
    Comment updateComment(Long commentId, Long userId, String content) throws ChallengeSphereException;
    
    /**
     * Soft delete a comment (set active = false)
     */
    void deleteComment(Long commentId, Long userId) throws ChallengeSphereException;
}