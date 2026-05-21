package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.ChangePasswordRequest;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.RefreshTokenRequest;
import com.ironside.challengesphere.api.request.SendOtpRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.request.VerifyOtpRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.SmsService;
import com.ironside.challengesphere.service.UserService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger logger = LogManager.getLogger(AuthController.class);

    private final SmsService smsService;
    private final EmailService emailService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthService authService;

    public AuthController(SmsService smsService,
                          EmailService emailService,
                          UserService userService,
                          UserRepository userRepository,
                          AuthService authService) {
        this.smsService = smsService;
        this.emailService = emailService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @PostMapping("/otp/send")
    public ResponseEntity<Map<String, Object>> sendOtp(@Valid @RequestBody SendOtpRequest request)
            throws ChallengeSphereException {
        String sid = smsService.sendOtp(request.getPhone());
        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "verificationSid", sid,
                "phone", request.getPhone()
        ));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<Map<String, Object>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request)
            throws ChallengeSphereException {
        boolean approved = smsService.verifyOtp(request.getPhone(), request.getCode());
        if (!approved) {
            throw new ChallengeSphereException("Invalid or expired verification code",
                    HttpStatus.BAD_REQUEST, logger);
        }
        return ResponseEntity.ok(Map.of(
                "status", "approved",
                "phone", request.getPhone()
        ));
    }

    @GetMapping("/username-availability")
    public ResponseEntity<Map<String, Object>> usernameAvailability(@RequestParam String username) {
        String candidate = username == null ? "" : username.trim();
        boolean valid = candidate.matches("^[a-zA-Z0-9_]{3,50}$");
        boolean available = valid && userRepository.findByUsername(candidate).isEmpty();
        return ResponseEntity.ok(Map.of(
                "username", candidate,
                "valid", valid,
                "available", available
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody UserRegistrationRequest request)
            throws ChallengeSphereException {
        if (StringUtils.hasText(request.getPhone()) && StringUtils.hasText(request.getOtpCode())) {
            boolean approved = smsService.verifyOtp(request.getPhone(), request.getOtpCode());
            if (!approved) {
                throw new ChallengeSphereException("Invalid or expired verification code",
                        HttpStatus.BAD_REQUEST, logger);
            }
            logger.info("OTP verified for phone={} during registration", request.getPhone());
        }

        userService.registerUser(request);

        userRepository.findByEmailId(request.getEmailId()).ifPresent(this::safelySendWelcome);

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(request.getUsername());
        loginRequest.setPassword(request.getPassword());
        AuthResponse response = authService.login(loginRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request)
            throws ChallengeSphereException {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request)
            throws ChallengeSphereException {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleSignIn(@Valid @RequestBody GoogleAuthRequest request)
            throws ChallengeSphereException {
        AuthResponse response = authService.loginWithGoogle(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<User> me(@AuthenticationPrincipal UserPrincipal principal)
            throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException("User not found", HttpStatus.UNAUTHORIZED, logger));
        return ResponseEntity.ok(user);
    }

    @PostMapping("/change-password")
    public ResponseEntity<MessageResponse> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        authService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword(), request.getConfirmPassword());
        return ResponseEntity.ok(new MessageResponse("Password changed successfully. Please sign in again."));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) RefreshTokenRequest request) {
        authService.logout(extractBearerToken(authorization), request != null ? request.getRefreshToken() : null);
        return ResponseEntity.ok(new MessageResponse("Logged out successfully"));
    }

    private String extractBearerToken(String authorization) {
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    private void safelySendWelcome(User user) {
        try {
            emailService.sendWelcomeEmail(user);
        } catch (Exception e) {
            logger.warn("Welcome email dispatch failed for userId={}: {}", user.getId(), e.getMessage());
        }
    }
}
