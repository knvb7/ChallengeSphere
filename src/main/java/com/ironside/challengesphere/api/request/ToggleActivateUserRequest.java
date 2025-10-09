package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ToggleActivateUserRequest {

    @NotNull(message = "User ID is required")
    private Long userId;
}
