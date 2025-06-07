package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.request.UserUpdateRequest;
import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private static final Logger logger = LogManager.getLogger(UserController.class);

    @Autowired
    private final UserService userService;

    @Autowired
    private final UserRepository userRepository;

//    @Autowired
//    private final AuthService authService;

//    @Autowired
//    private final PasswordEncoder passwordEncoder;

//    @GetMapping("/me")
//    @PreAuthorize("isAuthenticated()")
//    public ResponseEntity<UserProfileResponse> getCurrentUserProfile() {
//        logger.info("Fetching current user profile");
//        User currentUser = authService.getCurrentUser();
//        UserProfileResponse profileResponse = userService.getUserProfile(currentUser.getUserId());
//        logger.debug("Fetched profile for userId={}", currentUser.getUserId());
//        return ResponseEntity.ok(profileResponse);
//    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable Long userId) {
        logger.info("Fetching profile for userId={}", userId);
        return ResponseEntity.ok(userService.getUserProfile(userId));
    }

//    @PutMapping("/me")
//    @PreAuthorize("isAuthenticated()")
//    public ResponseEntity<UserProfileResponse> updateCurrentUserProfile(
//            @Valid @RequestBody UserUpdateRequest updateRequest) {
//        User currentUser = authService.getCurrentUser();
//        logger.info("Updating profile for userId={}", currentUser.getUserId());
//        UserProfileResponse updatedProfile = userService.updateUserProfile(
//                currentUser.getUserId(), updateRequest);
//        logger.debug("Updated profile: {}", updatedProfile);
//        return ResponseEntity.ok(updatedProfile);
//    }
//
//    @PostMapping("/me/change-password")
//    @PreAuthorize("isAuthenticated()")
//    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
//        User currentUser = authService.getCurrentUser();
//        logger.info("User {} requested password change", currentUser.getUserId());
//
//        // Verify old password
//        if (!passwordEncoder.matches(request.getOldPassword(), currentUser.getPasswordHash())) {
//            logger.warn("Password change failed for user {}: incorrect current password", currentUser.getUserId());
//            return ResponseEntity.badRequest()
//                    .body(new MessageResponse("Error: Current password is incorrect"));
//        }
//
//        // Update password
//        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
//        userRepository.save(currentUser);
//
//        logger.info("Password changed successfully for user {}", currentUser.getUserId());
//        return ResponseEntity.ok(new MessageResponse("Password changed successfully"));
//    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<UserProfileResponse>> getLeaderboard() {
        logger.info("Fetching leaderboard");
        return ResponseEntity.ok(userService.getLeaderboard());
    }

    @PostMapping("/registerNewUser")
    public ResponseEntity<?> registerNewUser(@Valid @RequestBody UserRegistrationRequest userRegistrationRequest) {
        logger.info("Registering new user: {}", userRegistrationRequest.getUsername());
        try {
            UserProfileResponse response = userService.registerNewUser(userRegistrationRequest);
            logger.info("User registered successfully: username={}", userRegistrationRequest.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Registration failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(e.getMessage()));
        }
    }

    // Main update endpoint remains for bulk updates
    @PutMapping("/{userId}")
    public ResponseEntity<?> updateUser(@PathVariable Long userId, @Valid @RequestBody UserUpdateRequest updateRequest) {
        logger.info("Updating user details for userId={}", userId);
        try {
            UserProfileResponse response = userService.updateUserProfile(userId, updateRequest);
            logger.info("User details updated successfully for userId={}", userId);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            logger.warn("User update failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.warn("User update failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(e.getMessage()));
        }
    }

    @PutMapping("/{userId}/bio")
    public ResponseEntity<?> updateUserBio(@PathVariable Long userId, @RequestParam String bio) {
        try {
            UserProfileResponse response = userService.updateUserBio(userId, bio);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(e.getMessage()));
        }
    }

    @PutMapping("/{userId}/location")
    public ResponseEntity<?> updateUserLocation(@PathVariable Long userId, @RequestParam String location) {
        try {
            UserProfileResponse response = userService.updateUserLocation(userId, location);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(e.getMessage()));
        }
    }

    @PutMapping("/{userId}/profile-picture")
    public ResponseEntity<?> updateProfilePicture(@PathVariable Long userId, @RequestParam String profilePicUrl) {
        try {
            UserProfileResponse response = userService.updateProfilePicture(userId, profilePicUrl);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(e.getMessage()));
        }
    }

    @PutMapping("/{userId}/preferences")
    public ResponseEntity<?> updateUserPreferences(
            @PathVariable Long userId,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String tone) {
        try {
            UserProfileResponse response = userService.updateUserPreferences(userId, language, tone);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(e.getMessage()));
        }
    }

    @PostMapping("/{userId}/follow-unfollow/{targetUserId}")
    public ResponseEntity<?> followOrUnfollowUser(
            @PathVariable Long userId,
            @PathVariable Long targetUserId) {
        try {
            userService.toggleFollow(userId, targetUserId);
            return ResponseEntity.ok(new MessageResponse("Successfully toggled the following for the user "+userId+" and targetUserId "+targetUserId));
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(e.getMessage()));
        }
    }

    @PostMapping("/toggleActivation")
    public ResponseEntity<MessageResponse> toggleActivation(@RequestParam Long userId) {
        try {
            int newStatus = userService.toggleActivation(userId);
            return ResponseEntity.ok(new MessageResponse("User activation status toggled to " + newStatus));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(e.getMessage()));
        }
    }

}
