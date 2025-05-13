package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.util.UserRole;
import lombok.Data;

import java.time.Instant;

@Data
public class UserProfileResponse {
    private Long userId;
    private String username;
    private String email;
    private String fullName;
    private String location;
    private String bio;
    private boolean active;
    private UserRole role;
    private String profilePicUrl;
    private int followersCount;
    private int followingCount;
    private Instant createdAt;
    private Instant updatedAt;
}
