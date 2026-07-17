package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.ChangePasswordRequest;
import com.ironside.challengesphere.api.request.ForgotPasswordRequest;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.RefreshTokenRequest;
import com.ironside.challengesphere.api.request.ResetPasswordRequest;
import com.ironside.challengesphere.api.request.SendOtpRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.request.VerifyOtpRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.api.response.MessageResponse;
import com.ironside.challengesphere.api.response.SelfUserResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.SecurityAccess;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.SmsService;
import com.ironside.challengesphere.service.UserService;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
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
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger logger = LogManager.getLogger(AuthController.class);
    private static final String GOOGLE_ONLY_AUTH_MESSAGE =
            "Password authentication is disabled. Use Google Sign-In.";

    private final SmsService smsService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final RedisCacheServiceImpl redisCacheServiceImpl;

    public AuthController(SmsService smsService,
                          UserService userService,
                          UserRepository userRepository,
                          AuthService authService,
                          RedisCacheServiceImpl redisCacheServiceImpl) {
        this.smsService = smsService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.authService = authService;
        this.redisCacheServiceImpl = redisCacheServiceImpl;
    }

    @PostMapping("/otp/send")
    public ResponseEntity<Map<String, Object>> sendOtp(@Valid @RequestBody SendOtpRequest request)
            throws ChallengeSphereException {
        throw googleOnlyAuthentication();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<Map<String, Object>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request)
            throws ChallengeSphereException {
        throw googleOnlyAuthentication();
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
        throw googleOnlyAuthentication();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request)
            throws ChallengeSphereException {
        throw googleOnlyAuthentication();
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

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) throws ChallengeSphereException {
        throw googleOnlyAuthentication();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) throws ChallengeSphereException {
        throw googleOnlyAuthentication();
    }

    @GetMapping("/me")
    public ResponseEntity<SelfUserResponse> me(@AuthenticationPrincipal UserPrincipal principal)
            throws ChallengeSphereException {
        Long userId = SecurityAccess.requireUserId(principal);
        User user = userService.getUserDetails(userId, null, null);
        return ResponseEntity.ok(SelfUserResponse.from(user));
    }

    @PostMapping("/change-password")
    public ResponseEntity<MessageResponse> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) throws ChallengeSphereException {
        throw googleOnlyAuthentication();
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

    private ChallengeSphereException googleOnlyAuthentication() {
        return new ChallengeSphereException(
                GOOGLE_ONLY_AUTH_MESSAGE,
                HttpStatus.GONE,
                logger
        );
    }
}
