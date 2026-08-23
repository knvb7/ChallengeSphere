package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.response.CommentResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Comment;

public interface CommentService {

    /** Get a cursor-paginated collection of comments enriched with author details. */
    CursorPageResponse<CommentResponse> getCommentsForSubmission(
            String submissionId,
            String callerId,
            int limit,
            String cursor
    ) throws ChallengeSphereException;

    /** Create a new comment on a submission */
    CommentResponse createComment(String submissionId, String userId, String content) throws ChallengeSphereException;

    /** Edit an existing comment */
    Comment updateComment(String commentId, String userId, String content) throws ChallengeSphereException;

    /** Soft delete a comment (set active = false) */
    void deleteComment(String commentId, String userId) throws ChallengeSphereException;
}
