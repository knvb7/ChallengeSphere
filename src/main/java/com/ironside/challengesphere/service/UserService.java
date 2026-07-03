package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowRelationshipResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.api.response.UserSearchResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface UserService {
    UserRegistrationResponse registerUser(UserRegistrationRequest request) throws ChallengeSphereException;

    User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException;

    UserSearchResponse searchUsers(String query, Long currentUserId, Integer page, Integer pageSize) throws ChallengeSphereException;

    UserSearchResponse getSuggestedUsers(Long currentUserId, Integer page, Integer pageSize) throws ChallengeSphereException;

    long getTotalUserCount();

    User updateUserDetails(Long userId, UpdateUserRequest updateUserRequest) throws ChallengeSphereException;

    FollowActionResponse follow(Long followerId, Long followingId) throws ChallengeSphereException;

    FollowActionResponse unfollow(Long followerId, Long followingId) throws ChallengeSphereException;

    FollowRelationshipResponse getFollowRelationship(Long viewerUserId, Long targetUserId) throws ChallengeSphereException;

    FollowersFollowingResponse getFollowers(GetFollowersFollowingRequest request) throws ChallengeSphereException;

    FollowersFollowingResponse getFollowing(GetFollowersFollowingRequest request) throws ChallengeSphereException;

    ToggleUserActivationResponse toggleUserActivation(ToggleActivateUserRequest request) throws ChallengeSphereException;

    void blockUser(Long blockerUserId, Long blockedUserId) throws ChallengeSphereException;

    void unblockUser(Long blockerUserId, Long blockedUserId) throws ChallengeSphereException;

    List<User> getBlockedUsers(Long blockerUserId) throws ChallengeSphereException;

    void reportUser(Long reporterUserId, Long reportedUserId, String reason, String details) throws ChallengeSphereException;

    boolean isBlockedEitherDirection(Long firstUserId, Long secondUserId);

    User getUserDetails(Long userId, String emailId, String username) throws ChallengeSphereException;
}
