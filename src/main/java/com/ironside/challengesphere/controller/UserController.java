package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.ChangePasswordRequest;
import com.ironside.challengesphere.api.request.UserUpdateRequest;
import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Autowired
    private final AuthService authService;

    @Autowired
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> getCurrentUserProfile() {
        logger.info("Fetching current user profile");
        User currentUser = authService.getCurrentUser();
        UserProfileResponse profileResponse = userService.getUserProfile(currentUser.getUserId());
        logger.debug("Fetched profile for userId={}", currentUser.getUserId());
        return ResponseEntity.ok(profileResponse);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable Long userId) {
        logger.info("Fetching profile for userId={}", userId);
        UserProfileResponse profileResponse = userService.getUserProfile(userId);
        logger.debug("Fetched profile: {}", profileResponse);
        return ResponseEntity.ok(profileResponse);
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> updateCurrentUserProfile(
            @Valid @RequestBody UserUpdateRequest updateRequest) {
        User currentUser = authService.getCurrentUser();
        logger.info("Updating profile for userId={}", currentUser.getUserId());
        UserProfileResponse updatedProfile = userService.updateUserProfile(
                currentUser.getUserId(), updateRequest);
        logger.debug("Updated profile: {}", updatedProfile);
        return ResponseEntity.ok(updatedProfile);
    }

    @PostMapping("/me/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        User currentUser = authService.getCurrentUser();
        logger.info("User {} requested password change", currentUser.getUserId());

        // Verify old password
        if (!passwordEncoder.matches(request.getOldPassword(), currentUser.getPasswordHash())) {
            logger.warn("Password change failed for user {}: incorrect current password", currentUser.getUserId());
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Error: Current password is incorrect"));
        }

        // Update password
        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(currentUser);

        logger.info("Password changed successfully for user {}", currentUser.getUserId());
        return ResponseEntity.ok(new MessageResponse("Password changed successfully"));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<UserProfileResponse>> getLeaderboard() {
        logger.info("Fetching leaderboard");
        List<UserProfileResponse> leaderboard = userService.getLeaderboard();
        logger.debug("Leaderboard size: {}", leaderboard.size());
        return ResponseEntity.ok(leaderboard);
    }

    @PostMapping("/registerNewUser")
    public ResponseEntity<?> registerNewUser(@Valid @RequestBody User userRequest) {
        logger.info("Registering new user: {}", userRequest.getUsername());
        // Check if username or email or phone number already exists
        if (userRepository.existsByUsername(userRequest.getUsername())) {
            logger.warn("Registration failed: Username {} is already taken", userRequest.getUsername());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageResponse("Error: Username is already taken!"));
        }
        if (userRepository.existsByEmailId(userRequest.getEmailId())) {
            logger.warn("Registration failed: Email {} is already in use", userRequest.getEmailId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageResponse("Error: Email is already in use!"));
        }
        if (userRequest.getPhoneNumber() != null && userRepository.existsByPhoneNumber(userRequest.getPhoneNumber())) {
            logger.warn("Registration failed: Phone number {} is already in use", userRequest.getPhoneNumber());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageResponse("Error: Phone number is already in use!"));
        }

        // Hash the password before saving
        userRequest.setPasswordHash(passwordEncoder.encode(userRequest.getPasswordHash()));

        // Set default values if not provided
        if (userRequest.getLanguagePreference() == null) {
            userRequest.setLanguagePreference("en");
        }
        if (userRequest.getPreferredTone() == null) {
            userRequest.setPreferredTone("motivational");
        }

        User savedUser = userRepository.save(userRequest);
        logger.info("User registered successfully: userId={}", savedUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(savedUser);
    }

    @PutMapping("/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> updateUser(@PathVariable Long userId, @Valid @RequestBody User userPayload) {
        logger.info("Updating user details for userId={}", userId);

        // Fetch the existing user
        User existingUser = userRepository.findById(userId).orElse(null);
        if (existingUser == null) {
            logger.warn("User update failed: userId={} not found", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("Error: User not found"));
        }

        // Validate email uniqueness if changed
        if (userPayload.getEmailId() != null && !userPayload.getEmailId().equals(existingUser.getEmailId())) {
            if (userRepository.existsByEmailId(userPayload.getEmailId())) {
                logger.warn("User update failed: Email {} is already in use", userPayload.getEmailId());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new MessageResponse("Error: Email is already in use!"));
            }
            existingUser.setEmailId(userPayload.getEmailId());
        }

        // Validate username uniqueness if changed
        if (userPayload.getUsername() != null && !userPayload.getUsername().equals(existingUser.getUsername())) {
            if (userRepository.existsByUsername(userPayload.getUsername())) {
                logger.warn("User update failed: Username {} is already taken", userPayload.getUsername());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new MessageResponse("Error: Username is already taken!"));
            }
            existingUser.setUsername(userPayload.getUsername());
        }

        // Validate phone number uniqueness if changed and not null
        if (userPayload.getPhoneNumber() != null && !userPayload.getPhoneNumber().equals(existingUser.getPhoneNumber())) {
            if (userRepository.existsByPhoneNumber(userPayload.getPhoneNumber())) {
                logger.warn("User update failed: Phone number {} is already in use", userPayload.getPhoneNumber());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new MessageResponse("Error: Phone number is already in use!"));
            }
            existingUser.setPhoneNumber(userPayload.getPhoneNumber());
        }

        // Update other fields if provided
        if (userPayload.getLanguagePreference() != null) {
            existingUser.setLanguagePreference(userPayload.getLanguagePreference());
        }
        if (userPayload.getPreferredTone() != null) {
            existingUser.setPreferredTone(userPayload.getPreferredTone());
        }
        if (userPayload.getLastLoginAt() != null) {
            existingUser.setLastLoginAt(userPayload.getLastLoginAt());
        }

        User updatedUser = userRepository.save(existingUser);
        logger.info("User details updated successfully for userId={}", userId);
        return ResponseEntity.ok(updatedUser);
    }

    @PostMapping("/{userId}/follow/{targetUserId}")
    public ResponseEntity<Void> followUser(
            @PathVariable Long userId,
            @PathVariable Long targetUserId) {
        userService.followUser(userId, targetUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}/unfollow/{targetUserId}")
    public ResponseEntity<Void> unfollowUser(
            @PathVariable Long userId,
            @PathVariable Long targetUserId) {
        userService.unfollowUser(userId, targetUserId);
        return ResponseEntity.ok().build();
    }


}
