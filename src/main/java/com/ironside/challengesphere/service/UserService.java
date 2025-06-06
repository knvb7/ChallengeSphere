package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.UserUpdateRequest;
import com.ironside.challengesphere.api.response.UserProfileResponse;

import java.util.List;

public interface UserService {
    
    /**
     * Gets user profile information by user ID
     * 
     * @param userId the ID of the user
     * @return user profile information
     */
    UserProfileResponse getUserProfile(Long userId);
    
    /**
     * Updates user profile information
     * 
     * @param userId the ID of the user to update
     * @param updateRequest request containing new profile information
     * @return updated user profile information
     */
    UserProfileResponse updateUserProfile(Long userId, UserUpdateRequest updateRequest);
    
    /**
     * Gets a leaderboard of users based on challenge participation and completions
     * 
     * @return list of user profiles sorted by achievement metrics
     */
    List<UserProfileResponse> getLeaderboard();

    void unfollowUser(Long userId, Long targetUserId);

    void followUser(Long userId, Long targetUserId);
}
