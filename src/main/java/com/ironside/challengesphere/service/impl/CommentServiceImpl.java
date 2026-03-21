package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.repository.CommentRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.CommentService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger logger = LogManager.getLogger(CommentServiceImpl.class);

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Override
    public Page<Comment> getCommentsForSubmission(Long submissionId, Pageable pageable) throws ChallengeSphereException {
        logger.info("Getting comments for submission: {}, page: {}, size: {}",
                submissionId, pageable.getPageNumber(), pageable.getPageSize());
        return commentRepository.findActiveCommentsBySubmissionId(submissionId, pageable);
    }

    @Override
    @Transactional
    public Comment createComment(Long submissionId, Long userId, String content) throws ChallengeSphereException {
        logger.info("Creating comment for submission: {} by user: {}", submissionId, userId);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (content == null || content.trim().isEmpty()) {
            throw new ChallengeSphereException(
                    "Comment content cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        Comment comment = new Comment();
        comment.setSubmissionId(submissionId);
        comment.setUserId(userId);
        comment.setContent(content.trim());
        comment.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        comment.setActive(true);

        Comment savedComment = commentRepository.save(comment);
        logger.info("Created comment with id: {} for submission: {}", savedComment.getId(), submissionId);

        cacheService.invalidateCommentsForSubmission(submissionId);
        updateCommentCountAsync(submission, true);

        return savedComment;
    }

    @Override
    @Transactional
    public Comment updateComment(Long commentId, Long userId, String content) throws ChallengeSphereException {
        logger.info("Updating comment: {} by user: {}", commentId, userId);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Comment not found with id: " + commentId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (!comment.getActive()) {
            throw new ChallengeSphereException("Cannot update a deleted comment", HttpStatus.BAD_REQUEST, logger);
        }
        if (!comment.getUserId().equals(userId)) {
            throw new ChallengeSphereException("User not authorized to update this comment", HttpStatus.FORBIDDEN, logger);
        }
        if (content == null || content.trim().isEmpty()) {
            throw new ChallengeSphereException("Comment content cannot be empty", HttpStatus.BAD_REQUEST, logger);
        }

        comment.setContent(content.trim());
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));

        Comment updatedComment = commentRepository.save(comment);
        logger.info("Updated comment with id: {}", commentId);

        cacheService.invalidateCommentsForSubmission(comment.getSubmissionId());
        return updatedComment;
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId, Long userId) throws ChallengeSphereException {
        logger.info("Deleting comment: {} by user: {}", commentId, userId);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Comment not found with id: " + commentId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (!comment.getActive()) {
            throw new ChallengeSphereException("Comment already deleted", HttpStatus.BAD_REQUEST, logger);
        }
        if (!comment.getUserId().equals(userId)) {
            throw new ChallengeSphereException("User not authorized to delete this comment", HttpStatus.FORBIDDEN, logger);
        }

        comment.setActive(false);
        comment.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        commentRepository.save(comment);

        logger.info("Soft deleted comment with id: {}", commentId);

        cacheService.invalidateCommentsForSubmission(comment.getSubmissionId());

        Submission submission = submissionRepository.findById(comment.getSubmissionId()).orElse(null);
        if (submission != null) {
            updateCommentCountAsync(submission, false);
        }
    }

    @Async
    @Transactional
    public void updateCommentCountAsync(Submission submission, boolean isAdded) {
        try {
            if (submission != null) {
                Long currentCount = submission.getCommentsCount();
                Long newCount = isAdded ? currentCount + 1 : Math.max(0, currentCount - 1);
                submission.setCommentsCount(newCount);
                submissionRepository.save(submission);
                logger.info("Updated comment count to {} for submission: {} ({})",
                        newCount, submission.getId(), isAdded ? "increased" : "decreased");
            }
        } catch (Exception e) {
            logger.error("Error updating comment count for submission {}: {}",
                    submission.getId(), e.getMessage(), e);
        }
    }
}