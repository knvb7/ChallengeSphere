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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private static final Logger logger = LogManager.getLogger(UserController.class);

    @Autowired
    private UserService userService;

//    @Autowired
//    private AuthService authService;

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

    @GetMapping
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

    @PutMapping
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

    @GetMapping("/{userId}/followers")
    public ResponseEntity<FollowersFollowingResponse> getFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) throws ChallengeSphereException {
        GetFollowersFollowingRequest request = new GetFollowersFollowingRequest();
        request.setUserId(userId);
        request.setPage(page);
        request.setPageSize(pageSize);
        FollowersFollowingResponse response = userService.getFollowers(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}/following")
    public ResponseEntity<FollowersFollowingResponse> getFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) throws ChallengeSphereException {
        GetFollowersFollowingRequest request = new GetFollowersFollowingRequest();
        request.setUserId(userId);
        request.setPage(page);
        request.setPageSize(pageSize);
        FollowersFollowingResponse response = userService.getFollowing(request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/activation")
    public ResponseEntity<ToggleUserActivationResponse> toggleUserActivation(@Valid @RequestBody ToggleActivateUserRequest request) throws ChallengeSphereException {
        ToggleUserActivationResponse response = userService.toggleUserActivation(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{userId}/profile-picture")
    public ResponseEntity<Map<String, Object>> uploadProfilePicture(
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) throws ChallengeSphereException {
        String profilePicUrl = body.getOrDefault("profilePicUrl", "");
        UpdateUserRequest request = new UpdateUserRequest();
        request.setUserId(userId);
        request.setProfilePicUrl(profilePicUrl);
        userService.updateUserDetails(request);
        return ResponseEntity.ok(Map.of("success", true, "profilePicUrl", profilePicUrl));
    }

    @PostMapping("/block")
    public ResponseEntity<Map<String, Object>> blockUser(@RequestBody Map<String, Long> body) {
        // TODO: persist block relationship
        return ResponseEntity.ok(Map.of("success", true, "message", "User blocked"));
    }

    @PostMapping("/unblock")
    public ResponseEntity<Map<String, Object>> unblockUser(@RequestBody Map<String, Long> body) {
        // TODO: persist unblock
        return ResponseEntity.ok(Map.of("success", true, "message", "User unblocked"));
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> reportUser(@RequestBody Map<String, Object> body) {
        // TODO: persist report
        return ResponseEntity.ok(Map.of("success", true, "message", "User reported"));
    }

    @GetMapping("/{userId}/blocked")
    public ResponseEntity<Map<String, Object>> getBlockedUsers(@PathVariable Long userId) {
        // TODO: return actual blocked users
        return ResponseEntity.ok(Map.of("users", List.of(), "totalCount", 0, "hasMore", false));
    }
}