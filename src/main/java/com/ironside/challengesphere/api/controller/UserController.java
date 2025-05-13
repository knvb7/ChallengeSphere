package com.ironside.challengesphere.api.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> getCurrentUserProfile() {
        User currentUser = authService.getCurrentUser();
        UserProfileResponse profileResponse = userService.getUserProfile(currentUser.getUserId());
        return ResponseEntity.ok(profileResponse);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable Long userId) {
        UserProfileResponse profileResponse = userService.getUserProfile(userId);
        return ResponseEntity.ok(profileResponse);
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> updateCurrentUserProfile(
            @Valid @RequestBody UserUpdateRequest updateRequest) {
        User currentUser = authService.getCurrentUser();
        UserProfileResponse updatedProfile = userService.updateUserProfile(
                currentUser.getUserId(), updateRequest);
        return ResponseEntity.ok(updatedProfile);
    }

    @PostMapping("/me/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        User currentUser = authService.getCurrentUser();
        
        // Verify old password
        if (!passwordEncoder.matches(request.getOldPassword(), currentUser.getPasswordHash())) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Error: Current password is incorrect"));
        }
        
        // Update password
        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(currentUser);
        
        return ResponseEntity.ok(new MessageResponse("Password changed successfully"));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<UserProfileResponse>> getLeaderboard() {
        List<UserProfileResponse> leaderboard = userService.getLeaderboard();
        return ResponseEntity.ok(leaderboard);
    }
}
