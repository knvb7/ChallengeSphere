package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.model.User;
import lombok.Data;

import java.sql.Timestamp;

@Data
public class CommentResponse {
    private Long commentId;
    private Long submissionId;
    private String content;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    // Author details
    private Long userId;
    private String username;
    private String profilePicUrl;

    public static CommentResponse from(Comment comment, User user) {
        CommentResponse response = new CommentResponse();
        response.setCommentId(comment.getId());
        response.setSubmissionId(comment.getSubmissionId());
        response.setContent(comment.getContent());
        response.setCreatedAt(comment.getCreatedAt());
        response.setUpdatedAt(comment.getUpdatedAt());
        response.setUserId(comment.getUserId());
        if (user != null) {
            response.setUsername(user.getUsername());
            response.setProfilePicUrl(user.getProfilePicUrl());
        }
        return response;
    }
}
