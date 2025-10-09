package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FollowActionRequest {

    @NotNull(message = "Follower ID is required")
    private Long followerId;

    @NotNull(message = "Following ID is required")
    private Long followingId;

    @NotNull(message = "Action is required")
    private Boolean follow; // true for follow, false for unfollow
}
