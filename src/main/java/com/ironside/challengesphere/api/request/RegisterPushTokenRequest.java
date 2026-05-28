package com.ironside.challengesphere.api.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RegisterPushTokenRequest {
    private String expoPushToken;
    private String platform;
    private String timezone;
}
