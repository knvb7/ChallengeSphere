package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ToggleActivateUserRequest {

    @NotBlank(message = "User ID is required")
    private String userId;
}
