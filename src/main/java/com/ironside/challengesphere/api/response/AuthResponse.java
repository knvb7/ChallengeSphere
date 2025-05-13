package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    private String accessToken;
    private String tokenType = "Bearer";
    private Long expiresIn;
    private Instant issuedAt;
    private Instant expiresAt;
    private String username;
    private String email;
    private String userId;
    
    public AuthResponse(String accessToken) {
        this.accessToken = accessToken;
    }
    
    public AuthResponse(String accessToken, Long expiresIn, Instant issuedAt) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
        this.issuedAt = issuedAt;
        this.expiresAt = issuedAt.plusMillis(expiresIn);
    }
}
