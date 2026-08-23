package com.ironside.challengesphere.api.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ironside.challengesphere.model.User;
import lombok.Data;

import java.sql.Timestamp;

@Data
public class UserRegistrationResponse {
    private boolean success;
    private String message;
    private String userId;
    private String username;
    private String emailId;
    private String phone;
    private Timestamp createdAt;
    private Integer genuinenessScore;
    @JsonProperty("genuinityScore")
    private Integer genuinityScore;

    public static UserRegistrationResponse success(User user) {
        UserRegistrationResponse response = new UserRegistrationResponse();
        response.setSuccess(true);
        response.setMessage("User registered successfully");
        response.setUserId(user.getId() == null ? null : user.getId().toString());
        response.setUsername(user.getUsername());
        response.setEmailId(user.getEmailId());
        response.setPhone(user.getPhone());
        response.setCreatedAt(user.getCreatedAt());
        response.setGenuinenessScore(user.getGenuinenessScore());
        response.setGenuinityScore(user.getGenuinenessScore());
        return response;
    }

    public static UserRegistrationResponse failure(String message) {
        UserRegistrationResponse response = new UserRegistrationResponse();
        response.setSuccess(false);
        response.setMessage(message);
        return response;
    }
}
