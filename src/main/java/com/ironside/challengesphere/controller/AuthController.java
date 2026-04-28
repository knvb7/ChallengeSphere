package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.SendOtpRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.request.VerifyOtpRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.api.response.UserRegistrationResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.SmsService;
import com.ironside.challengesphere.service.UserService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping("/register")
    public ResponseEntity<UserRegistrationResponse> register(@Valid @RequestBody UserRegistrationRequest request)
            throws ChallengeSphereException {
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            if (request.getOtpCode() == null || request.getOtpCode().isBlank()) {
                throw new ChallengeSphereException(
                        "OTP code is required when phone is provided. Call /auth/otp/send first.",
                        HttpStatus.BAD_REQUEST, logger);
            }
            boolean approved = smsService.verifyOtp(request.getPhone(), request.getOtpCode());
            if (!approved) {
                throw new ChallengeSphereException("Invalid or expired verification code",
                        HttpStatus.BAD_REQUEST, logger);
            }
            logger.info("OTP verified for phone={} during registration", request.getPhone());
        }

        UserRegistrationResponse response = userService.registerUser(request);

        userRepository.findByEmailId(request.getEmailId()).ifPresent(this::safelySendWelcome);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleSignIn(@Valid @RequestBody GoogleAuthRequest request)
            throws ChallengeSphereException {
        AuthResponse response = authService.loginWithGoogle(request);
        return ResponseEntity.ok(response);
    }

    private void safelySendWelcome(User user) {
        try {
            emailService.sendWelcomeEmail(user);
        } catch (Exception e) {
            logger.warn("Welcome email dispatch failed for userId={}: {}", user.getId(), e.getMessage());
        }
    }
}
