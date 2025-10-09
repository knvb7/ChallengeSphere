package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowActionResponse {
    
    private Long followerId;
    private Long followingId;
    private Boolean isFollowing;
    private String message;
    
    public static FollowActionResponse success(Long followerId, Long followingId, Boolean isFollowing) {
        String message = isFollowing ? "Successfully followed user" : "Successfully unfollowed user";
        return new FollowActionResponse(followerId, followingId, isFollowing, message);
    }
}