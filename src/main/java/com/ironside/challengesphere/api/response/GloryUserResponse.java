package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.User;
import lombok.Data;

import java.sql.Timestamp;

@Data
public class GloryUserResponse {
    private Long id;
    private Long submissionId;
    private Long userId;
    private Timestamp createdAt;
    private Boolean active;
    private String username;
    private String profilePicUrl;
    private String bio;

    public static GloryUserResponse from(Glory glory, User user) {
        GloryUserResponse response = new GloryUserResponse();
        response.setId(glory.getId());
        response.setSubmissionId(glory.getSubmissionId());
        response.setUserId(glory.getUserId());
        response.setCreatedAt(glory.getCreatedAt());
        response.setActive(glory.getActive());
        if (user != null) {
            response.setUsername(user.getUsername());
            response.setProfilePicUrl(user.getProfilePicUrl());
            response.setBio(user.getBio());
        }
        return response;
    }
}
