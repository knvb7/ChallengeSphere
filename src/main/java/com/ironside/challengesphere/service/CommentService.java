package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.CommentResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CommentService {

    /** Get paginated comments for a submission, enriched with author details */
    Page<CommentResponse> getCommentsForSubmission(Long submissionId, Long callerId, Pageable pageable) throws ChallengeSphereException;

    /** Create a new comment on a submission */
    CommentResponse createComment(Long submissionId, Long userId, String content) throws ChallengeSphereException;

    /** Edit an existing comment */
    Comment updateComment(Long commentId, Long userId, String content) throws ChallengeSphereException;

    /** Soft delete a comment (set active = false) */
    void deleteComment(Long commentId, Long userId) throws ChallengeSphereException;
}
