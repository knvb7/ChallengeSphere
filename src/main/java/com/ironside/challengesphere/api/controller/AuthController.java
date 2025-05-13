package com.ironside.challengesphere.api.controller;

import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody UserRegistrationRequest registerRequest) {
        // Check if a username or email already exists in a single database query
        if (userRepository.existsByUsernameOrEmail(registerRequest.getUsername(), registerRequest.getEmail())) {
            // For better UX, determine the specific conflict
            if (userRepository.existsByUsername(registerRequest.getUsername())) {
                return ResponseEntity
                        .badRequest()
                        .body(new MessageResponse("Error: Username is already taken!"));
            } else {
                return ResponseEntity
                        .badRequest()
                        .body(new MessageResponse("Error: Email is already in use!"));
            }
        }

        // Create a new user account
        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setEmailId(registerRequest.getEmail());
        user.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        // Set the user role to USER by default (admin roles should be assigned in a different process)
        user.setCreatedAt(OffsetDateTime.now());

        userRepository.save(user);

        // Generate JWT for the newly registered user
        AuthResponse authResponse = authService.authenticateUser(
                registerRequest.getUsername(), 
                registerRequest.getPassword());

        return ResponseEntity.status(HttpStatus.CREATED).body(authResponse);
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@Valid @RequestBody LoginRequest loginRequest) {
        try {
            AuthResponse authResponse = authService.authenticateUser(
                    loginRequest.getUsername(),
                    loginRequest.getPassword()
            );
            return ResponseEntity.ok(authResponse);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Error: Invalid username or password"));
        }
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestBody String refreshToken) {
        try {
            AuthResponse newTokens = authService.refreshToken(refreshToken);
            return ResponseEntity.ok(newTokens);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Error: Invalid or expired refresh token"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logoutUser() {
        // This would invalidate the token on the server-side if you're tracking active tokens
        // Otherwise, client-side logout is managed by the frontend removing the stored token
        return ResponseEntity.ok(new MessageResponse("User logged out successfully"));
    }
}
