package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowRelationshipResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.api.response.PublicUserResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.api.response.UserSearchResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import org.springframework.stereotype.Service;

@Service
public interface UserService {
    UserRegistrationResponse registerUser(UserRegistrationRequest request) throws ChallengeSphereException;

    User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException;

    UserSearchResponse searchUsers(String query, String currentUserId, Integer limit, String cursor) throws ChallengeSphereException;

    UserSearchResponse getSuggestedUsers(String currentUserId, Integer limit, String cursor) throws ChallengeSphereException;

    long getTotalUserCount();

    User updateUserDetails(String userId, UpdateUserRequest updateUserRequest) throws ChallengeSphereException;

    FollowActionResponse follow(String followerId, String followingId) throws ChallengeSphereException;

    FollowActionResponse unfollow(String followerId, String followingId) throws ChallengeSphereException;

    FollowRelationshipResponse getFollowRelationship(String viewerUserId, String targetUserId) throws ChallengeSphereException;

    FollowersFollowingResponse getFollowers(GetFollowersFollowingRequest request) throws ChallengeSphereException;

    FollowersFollowingResponse getFollowing(GetFollowersFollowingRequest request) throws ChallengeSphereException;

    ToggleUserActivationResponse toggleUserActivation(ToggleActivateUserRequest request) throws ChallengeSphereException;

    void blockUser(String blockerUserId, String blockedUserId) throws ChallengeSphereException;

    void unblockUser(String blockerUserId, String blockedUserId) throws ChallengeSphereException;

    CursorPageResponse<PublicUserResponse> getBlockedUsers(
            String blockerUserId,
            int limit,
            String cursor
    ) throws ChallengeSphereException;

    void reportUser(String reporterUserId, String reportedUserId, String reason, String details) throws ChallengeSphereException;

    boolean isBlockedEitherDirection(String firstUserId, String secondUserId);

    User getUserDetails(String userId, String emailId, String username) throws ChallengeSphereException;
}
