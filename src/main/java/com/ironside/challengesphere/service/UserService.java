package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.FollowActionRequest;
import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;

public interface UserService {
    User getUser(GetUserPayload getUserPayload) throws ChallengeSphereException;
    User updateUserDetails(UpdateUserRequest updateUserRequest) throws ChallengeSphereException;
    FollowActionResponse followAction(FollowActionRequest followActionRequest) throws ChallengeSphereException;
    FollowersFollowingResponse getFollowers(GetFollowersFollowingRequest request) throws ChallengeSphereException;
    FollowersFollowingResponse getFollowing(GetFollowersFollowingRequest request) throws ChallengeSphereException;
    ToggleUserActivationResponse toggleUserActivation(ToggleActivateUserRequest request) throws ChallengeSphereException;
}
