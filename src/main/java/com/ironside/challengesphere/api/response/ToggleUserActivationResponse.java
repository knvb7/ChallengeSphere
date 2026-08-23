package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToggleUserActivationResponse {

    private String userId;
    private Boolean active;
    private String message;

    public static ToggleUserActivationResponse success(String userId, Boolean newStatus, String action) {
        return new ToggleUserActivationResponse(userId, newStatus, "User successfully " + action);
    }
}
