package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;

public record StravaAuthorizeUrlRequest(@NotBlank String redirectUri) {
}
