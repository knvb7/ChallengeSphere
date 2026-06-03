package com.ironside.challengesphere.integration.strava;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StravaTokenResponse {
    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("expires_at")
    private Long expiresAt;

    @JsonProperty("expires_in")
    private Long expiresIn;

    private String scope;
    private StravaAthlete athlete;
}
