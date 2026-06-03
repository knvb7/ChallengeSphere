package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;

public record StravaConnectRequest(
        @NotBlank String code,
        @NotBlank String state,
        @NotBlank String redirectUri,
        String scope
) {
}
