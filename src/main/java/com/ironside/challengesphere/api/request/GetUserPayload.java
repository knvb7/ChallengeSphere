package com.ironside.challengesphere.api.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetUserPayload {
    private String userId;
    private String currentUserId;
    private String emailId;
    private String phone;
    private String username;
}
