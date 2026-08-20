package com.ironside.challengesphere.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppleAuthRequest {

    @NotBlank
    @Size(max = 16_000)
    private String identityToken;

    @NotBlank
    @Size(max = 4_000)
    private String authorizationCode;

    @NotBlank
    @Size(max = 256)
    private String rawNonce;

    @NotBlank
    @Size(max = 255)
    private String user;

    @Size(max = 255)
    private String email;

    @Valid
    private FullName fullName;

    @Data
    public static class FullName {
        @Size(max = 50)
        private String namePrefix;
        @Size(max = 120)
        private String givenName;
        @Size(max = 120)
        private String middleName;
        @Size(max = 120)
        private String familyName;
        @Size(max = 50)
        private String nameSuffix;
        @Size(max = 120)
        private String nickname;
    }
}
