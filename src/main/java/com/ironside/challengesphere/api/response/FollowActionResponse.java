package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowActionResponse {
    
    private String followerId;
    private String followingId;
    private Boolean isFollowing;
    private String message;
    
    public static FollowActionResponse success(String followerId, String followingId, Boolean isFollowing) {
        String message = isFollowing ? "Successfully followed user" : "Successfully unfollowed user";
        return new FollowActionResponse(followerId, followingId, isFollowing, message);
    }
}