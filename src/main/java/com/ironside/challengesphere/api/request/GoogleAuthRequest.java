package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleAuthRequest {

    @NotBlank(message = "ID token is required")
    private String idToken;
}
