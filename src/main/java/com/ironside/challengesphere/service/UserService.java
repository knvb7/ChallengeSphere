package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.FollowActionRequest;
import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import org.springframework.stereotype.Service;

@Service
public interface UserService {
    UserRegistrationResponse registerUser(UserRegistrationRequest request) throws ChallengeSphereException;
    User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException;
    User updateUserDetails(UpdateUserRequest updateUserRequest) throws ChallengeSphereException;
    FollowActionResponse follow(Long followerId, Long followingId) throws ChallengeSphereException;
    FollowActionResponse unfollow(Long followerId, Long followingId) throws ChallengeSphereException;
    FollowersFollowingResponse getFollowers(GetFollowersFollowingRequest request) throws ChallengeSphereException;
    FollowersFollowingResponse getFollowing(GetFollowersFollowingRequest request) throws ChallengeSphereException;
    ToggleUserActivationResponse toggleUserActivation(ToggleActivateUserRequest request) throws ChallengeSphereException;
}
