//package com.ironside.challengesphere.controller;
//
//import com.ironside.challengesphere.api.request.GoogleAuthRequest;
//import com.ironside.challengesphere.api.request.LoginRequest;
//import com.ironside.challengesphere.api.request.RefreshTokenRequest;
//import com.ironside.challengesphere.api.request.UserRegistrationRequest;
//import com.ironside.challengesphere.api.response.AuthResponse;
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import com.ironside.challengesphere.model.User;
//import com.ironside.challengesphere.service.AuthService;
//import jakarta.validation.Valid;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/api/v1/auth")
//public class AuthController {
//
//    private final AuthService authService;
//
//    public AuthController(AuthService authService) {
//        this.authService = authService;
//    }
//
//    @PostMapping("/register")
//    public ResponseEntity<AuthResponse> register(@Valid @RequestBody UserRegistrationRequest request)
//            throws ChallengeSphereException {
//        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
//    }
//
//    @PostMapping("/login")
//    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request)
//            throws ChallengeSphereException {
//        return ResponseEntity.ok(authService.login(request));
//    }
//
//    @PostMapping("/refresh")
//    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request)
//            throws ChallengeSphereException {
//        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
//    }
//
//    @GetMapping("/me")
//    public ResponseEntity<User> me() throws ChallengeSphereException {
//        return ResponseEntity.ok(authService.getCurrentUser());
//    }
//
//    @PostMapping("/google")
//    public ResponseEntity<AuthResponse> googleLogin(@Valid @RequestBody GoogleAuthRequest request)
//            throws ChallengeSphereException {
//        return ResponseEntity.ok(authService.loginWithGoogle(request));
//    }
//
//    @PostMapping("/logout")
//    public ResponseEntity<Void> logout() {
//        return ResponseEntity.ok().build();
//    }
//}
