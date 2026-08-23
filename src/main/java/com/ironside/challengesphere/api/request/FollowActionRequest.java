package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FollowActionRequest {

    @NotBlank(message = "Follower ID is required")
    private String followerId;

    @NotBlank(message = "Following ID is required")
    private String followingId;

    @NotNull(message = "Action is required")
    private Boolean follow; // true for follow, false for unfollow
}
