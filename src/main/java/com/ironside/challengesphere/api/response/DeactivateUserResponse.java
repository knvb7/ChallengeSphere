package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeactivateUserResponse {

    private Long userId;
    private Boolean active;
    private String message;

    public static DeactivateUserResponse success(Long userId) {
        return new DeactivateUserResponse(userId, false, "User successfully deactivated");
    }
}
