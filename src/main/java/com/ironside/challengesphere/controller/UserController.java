package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
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
import com.ironside.challengesphere.service.UserService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private static final Logger logger = LogManager.getLogger(UserController.class);

    @Autowired
    private UserService userService;

    /**
     * Register a new user
     * POST /api/v1/users/register
     */
    @PostMapping("/register")
    public ResponseEntity<UserRegistrationResponse> registerUser(@Valid @RequestBody UserRegistrationRequest request) throws ChallengeSphereException {
        logger.info("Registration request received for username: {}", request.getUsername());
        UserRegistrationResponse response = userService.registerUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/getUser")
    public ResponseEntity<User> getUser(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String emailId,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String username) throws ChallengeSphereException {
        GetUserPayload getUserPayload = new GetUserPayload();
        getUserPayload.setUserId(userId);
        getUserPayload.setEmailId(emailId);
        getUserPayload.setPhone(phone);
        getUserPayload.setUsername(username);
        User user = userService.getUser(getUserPayload);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/updateUserDetails")
    public ResponseEntity<User> updateUserDetails(@Valid @RequestBody UpdateUserRequest updateUserRequest) throws ChallengeSphereException {
        User updatedUser = userService.updateUserDetails(updateUserRequest);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * Follow a user
     * POST /api/v1/users/follow?followerId=X&followingId=Y
     */
    @PostMapping("/follow")
    public ResponseEntity<FollowActionResponse> followUser(
            @RequestParam Long followerId,
            @RequestParam Long followingId) throws ChallengeSphereException {
        logger.info("Follow request - followerId: {}, followingId: {}", followerId, followingId);
        FollowActionResponse response = userService.follow(followerId, followingId);
        return ResponseEntity.ok(response);
    }

    /**
     * Unfollow a user
     * POST /api/v1/users/unfollow?followerId=X&followingId=Y
     */
    @PostMapping("/unfollow")
    public ResponseEntity<FollowActionResponse> unfollowUser(
            @RequestParam Long followerId,
            @RequestParam Long followingId) throws ChallengeSphereException {
        logger.info("Unfollow request - followerId: {}, followingId: {}", followerId, followingId);
        FollowActionResponse response = userService.unfollow(followerId, followingId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/getFollowers")
    public ResponseEntity<FollowersFollowingResponse> getFollowers(@Valid @RequestBody GetFollowersFollowingRequest request) throws ChallengeSphereException {
        FollowersFollowingResponse response = userService.getFollowers(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/getFollowing")
    public ResponseEntity<FollowersFollowingResponse> getFollowing(@Valid @RequestBody GetFollowersFollowingRequest request) throws ChallengeSphereException {
        FollowersFollowingResponse response = userService.getFollowing(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/toggleActivation")
    public ResponseEntity<ToggleUserActivationResponse> toggleUserActivation(@Valid @RequestBody ToggleActivateUserRequest request) throws ChallengeSphereException {
        ToggleUserActivationResponse response = userService.toggleUserActivation(request);
        return ResponseEntity.ok(response);
    }
}