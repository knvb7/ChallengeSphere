package com.ironside.challengesphere.api.request;

import lombok.Data;

@Data
public class GoogleAuthRequest {

    private String idToken;

    private String accessToken;
}
