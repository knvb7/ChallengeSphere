package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ToggleActivateUserRequest;
import com.ironside.challengesphere.api.request.GetFollowersFollowingRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.request.GetUserPayload;
import com.ironside.challengesphere.api.request.UpdateUserRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.ToggleUserActivationResponse;
import com.ironside.challengesphere.api.response.FollowActionResponse;
import com.ironside.challengesphere.api.response.FollowRelationshipResponse;
import com.ironside.challengesphere.api.response.FollowersFollowingResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.api.response.UserSearchResponse;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.PublicUserResponse;
import com.ironside.challengesphere.api.response.SelfUserResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.UserService;
import com.ironside.challengesphere.service.impl.ProfilePhotoService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private static final Logger logger = LogManager.getLogger(UserController.class);
    private static final String GOOGLE_ONLY_AUTH_MESSAGE =
            "Password authentication is disabled. Use Google Sign-In.";

    @Autowired
    private UserService userService;

    @Autowired
    private ProfilePhotoService profilePhotoService;

//    @Autowired
//    private AuthService authService;

    /**
     * Register a new user
     * POST /api/v1/users/register
     */
    @PostMapping("/register")
    public ResponseEntity<UserRegistrationResponse> registerUser(@Valid @RequestBody UserRegistrationRequest request) throws ChallengeSphereException {
        throw new ChallengeSphereException(
                GOOGLE_ONLY_AUTH_MESSAGE,
                HttpStatus.GONE,
                logger
        );
    }

    @GetMapping
    public ResponseEntity<PublicUserResponse> getUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(required = false) String emailId,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String username) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        if ((emailId != null && !emailId.isBlank()) || (phone != null && !phone.isBlank())) {
            userId = actorUserId;
            emailId = null;
            phone = null;
            username = null;
        }
        GetUserPayload getUserPayload = new GetUserPayload();
        getUserPayload.setUserId(userId);
        getUserPayload.setCurrentUserId(actorUserId);
        getUserPayload.setEmailId(emailId);
        getUserPayload.setPhone(phone);
        getUserPayload.setUsername(username);
        User user = userService.getUser(getUserPayload);
        return ResponseEntity.ok(PublicUserResponse.from(user));
    }

    @GetMapping("/search")
    public ResponseEntity<UserSearchResponse> searchUsers(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String query,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        UserSearchResponse response = userService.searchUsers(query, actorUserId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/suggestions")
    public ResponseEntity<UserSearchResponse> getSuggestedUsers(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        UserSearchResponse response = userService.getSuggestedUsers(actorUserId, page, pageSize);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> getTotalUserCount() {
        long totalUsers = userService.getTotalUserCount();
        return ResponseEntity.ok(Map.of("totalUsers", totalUsers));
    }

    @PutMapping
    public ResponseEntity<SelfUserResponse> updateUserDetails(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateUserRequest updateUserRequest) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        User updatedUser = userService.updateUserDetails(actorUserId, updateUserRequest);
        return ResponseEntity.ok(SelfUserResponse.from(updatedUser));
    }

    /**
     * Follow a user
     * POST /api/v1/users/follow?followerId=X&followingId=Y
     */
    @PostMapping("/follow")
    public ResponseEntity<FollowActionResponse> followUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long followerId,
            @RequestParam Long followingId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        if (followerId != null) {
            SecurityAccess.requireSelf(principal, followerId);
        }
        logger.info("Follow request - followerId: {}, followingId: {}", actorUserId, followingId);
        FollowActionResponse response = userService.follow(actorUserId, followingId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}/relationship")
    public ResponseEntity<FollowRelationshipResponse> getFollowRelationship(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        FollowRelationshipResponse response = userService.getFollowRelationship(actorUserId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Unfollow a user
     * POST /api/v1/users/unfollow?followerId=X&followingId=Y
     */
    @PostMapping("/unfollow")
    public ResponseEntity<FollowActionResponse> unfollowUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long followerId,
            @RequestParam Long followingId) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        if (followerId != null) {
            SecurityAccess.requireSelf(principal, followerId);
        }
        logger.info("Unfollow request - followerId: {}, followingId: {}", actorUserId, followingId);
        FollowActionResponse response = userService.unfollow(actorUserId, followingId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}/followers")
    public ResponseEntity<FollowersFollowingResponse> getFollowers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) throws ChallengeSphereException {
        GetFollowersFollowingRequest request = new GetFollowersFollowingRequest();
        request.setUserId(userId);
        request.setCurrentUserId(SecurityAccess.requireUserId(principal));
        request.setPage(page);
        request.setPageSize(pageSize);
        FollowersFollowingResponse response = userService.getFollowers(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}/following")
    public ResponseEntity<FollowersFollowingResponse> getFollowing(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) throws ChallengeSphereException {
        GetFollowersFollowingRequest request = new GetFollowersFollowingRequest();
        request.setUserId(userId);
        request.setCurrentUserId(SecurityAccess.requireUserId(principal));
        request.setPage(page);
        request.setPageSize(pageSize);
        FollowersFollowingResponse response = userService.getFollowing(request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/activation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ToggleUserActivationResponse> toggleUserActivation(@Valid @RequestBody ToggleActivateUserRequest request) throws ChallengeSphereException {
        ToggleUserActivationResponse response = userService.toggleUserActivation(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{userId}/profile-picture")
    public ResponseEntity<Map<String, Object>> uploadProfilePicture(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) throws ChallengeSphereException {
        SecurityAccess.requireSelf(principal, userId);
        String profilePicUrl = body.getOrDefault("profilePicUrl", "");
        UpdateUserRequest request = new UpdateUserRequest();
        request.setProfilePicUrl(profilePicUrl);
        userService.updateUserDetails(userId, request);
        return ResponseEntity.ok(Map.of("success", true, "profilePicUrl", profilePicUrl));
    }

    @PostMapping("/{userId}/profile-picture/presigned-url")
    public ResponseEntity<PresignedUrlsResponse> generateProfilePhotoUpload(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody GeneratePresignedUrlsRequest.MediaFileInfo file)
            throws ChallengeSphereException {
        SecurityAccess.requireSelf(principal, userId);
        return ResponseEntity.ok(profilePhotoService.generateUpload(userId, file));
    }

    @PostMapping("/block")
    public ResponseEntity<Map<String, Object>> blockUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> body) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        Long targetUserId = extractTargetUserId(body);
        userService.blockUser(actorUserId, targetUserId);
        return ResponseEntity.ok(Map.of("success", true, "message", "User blocked"));
    }

    @PostMapping("/unblock")
    public ResponseEntity<Map<String, Object>> unblockUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> body) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        Long targetUserId = extractTargetUserId(body);
        userService.unblockUser(actorUserId, targetUserId);
        return ResponseEntity.ok(Map.of("success", true, "message", "User unblocked"));
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> reportUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> body) throws ChallengeSphereException {
        Long actorUserId = SecurityAccess.requireUserId(principal);
        Long targetUserId = extractTargetUserId(body);
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        String details = body.get("details") == null ? null : body.get("details").toString();
        userService.reportUser(actorUserId, targetUserId, reason, details);
        return ResponseEntity.ok(Map.of("success", true, "message", "User reported"));
    }

    @GetMapping("/{userId}/blocked")
    public ResponseEntity<Map<String, Object>> getBlockedUsers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId) throws ChallengeSphereException {
        SecurityAccess.requireSelf(principal, userId);
        List<User> users = userService.getBlockedUsers(userId);
        return ResponseEntity.ok(Map.of(
                "users", PublicUserResponse.fromUsers(users),
                "totalCount", users.size(),
                "hasMore", false
        ));
    }

    private Long extractTargetUserId(Map<String, Object> body) throws ChallengeSphereException {
        Object raw = body.getOrDefault("blockedUserId",
                body.getOrDefault("targetUserId",
                        body.getOrDefault("reportedUserId", body.get("userId"))));
        if (raw == null) {
            throw new ChallengeSphereException("targetUserId is required", HttpStatus.BAD_REQUEST, logger);
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            throw new ChallengeSphereException("targetUserId must be a valid number", HttpStatus.BAD_REQUEST, logger);
        }
    }
}
