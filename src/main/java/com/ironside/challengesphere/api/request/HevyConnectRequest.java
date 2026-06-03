package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;

public record HevyConnectRequest(@NotBlank String apiKey) {
}
