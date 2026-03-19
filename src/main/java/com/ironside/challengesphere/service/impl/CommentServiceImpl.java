package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.repository.CommentRepository;
import com.ironside.challengesphere.service.CommentService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger logger = LogManager.getLogger(CommentServiceImpl.class);

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Override
    public Page<Comment> getCommentsForPost(UUID postId, Pageable pageable) throws ChallengeSphereException {
        logger.info("Getting comments for post: {}, page: {}, size: {}",
                postId, pageable.getPageNumber(), pageable.getPageSize());

        Page<Comment> result = commentRepository.findActiveCommentsByPostId(postId, pageable);
        return result;
    }

    @Override
    @Transactional
    public Comment createComment(UUID postId, Long userId, String content) throws ChallengeSphereException {
        logger.info("Creating comment for post: {} by user: {}", postId, userId);

        // Validate content
        if (content == null || content.trim().isEmpty()) {
            throw new ChallengeSphereException(
                    "Comment content cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Create comment
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setContent(content.trim());
        comment.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        comment.setActive(true);

        Comment savedComment = commentRepository.save(comment);
        logger.info("Created comment with id: {} for post: {}", savedComment.getId(), postId);

        // Invalidate comments cache for this post
        cacheService.invalidateCommentsForPost(postId);

        return savedComment;
    }

    @Override
    @Transactional
    public Comment updateComment(Long commentId, Long userId, String content) throws ChallengeSphereException {
        logger.info("Updating comment: {} by user: {}", commentId, userId);

        // Fetch the comment
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Comment not found with id: " + commentId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        // Check if comment is active
        if (!comment.getActive()) {
            throw new ChallengeSphereException(
                    "Cannot update a deleted comment",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Verify user owns the comment
        if (!comment.getUserId().equals(userId)) {
            throw new ChallengeSphereException(
                    "User not authorized to update this comment",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        // Validate content
        if (content == null || content.trim().isEmpty()) {
            throw new ChallengeSphereException(
                    "Comment content cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Update comment
        comment.setContent(content.trim());
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));

        Comment updatedComment = commentRepository.save(comment);
        logger.info("Updated comment with id: {}", commentId);

        // Invalidate comments cache for this post
        cacheService.invalidateCommentsForPost(comment.getPostId());

        return updatedComment;
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId, Long userId) throws ChallengeSphereException {
        logger.info("Deleting comment: {} by user: {}", commentId, userId);

        // Fetch the comment
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Comment not found with id: " + commentId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        // Check if comment is already deleted
        if (!comment.getActive()) {
            throw new ChallengeSphereException(
                    "Comment already deleted",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        // Verify user owns the comment
        if (!comment.getUserId().equals(userId)) {
            throw new ChallengeSphereException(
                    "User not authorized to delete this comment",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        // Soft delete
        comment.setActive(false);
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        commentRepository.save(comment);

        logger.info("Soft deleted comment with id: {}", commentId);

        // Invalidate comments cache for this post
        cacheService.invalidateCommentsForPost(comment.getPostId());
    }
}