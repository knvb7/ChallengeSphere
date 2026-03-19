//package com.ironside.challengesphere.service.impl;
//
//import com.ironside.challengesphere.api.request.GoogleAuthRequest;
//import com.ironside.challengesphere.api.request.LoginRequest;
//import com.ironside.challengesphere.api.request.UserRegistrationRequest;
//import com.ironside.challengesphere.api.response.AuthResponse;
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import com.ironside.challengesphere.model.User;
//import com.ironside.challengesphere.repository.UserRepository;
//import com.ironside.challengesphere.security.JwtService;
//import com.ironside.challengesphere.security.UserPrincipal;
//import com.ironside.challengesphere.service.AuthService;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpStatus;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.client.RestTemplate;
//
//import java.sql.Timestamp;
//import java.time.Instant;
//import java.util.Map;
//import java.util.Optional;
//import java.util.UUID;
//
//@Service
//public class AuthServiceImpl implements AuthService {
//
//    private static final Logger logger = LogManager.getLogger(AuthServiceImpl.class);
//
//    private final UserRepository userRepository;
//    private final PasswordEncoder passwordEncoder;
//    private final JwtService jwtService;
//
//    @Value("${google.client.id:}")
//    private String googleClientId;
//
//    public AuthServiceImpl(UserRepository userRepository,
//                           PasswordEncoder passwordEncoder,
//                           JwtService jwtService) {
//        this.userRepository = userRepository;
//        this.passwordEncoder = passwordEncoder;
//        this.jwtService = jwtService;
//    }
//
//    @Override
//    @Transactional
//    public AuthResponse register(UserRegistrationRequest request) throws ChallengeSphereException {
//        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
//            throw new ChallengeSphereException("Username already exists", HttpStatus.CONFLICT, logger);
//        }
//        if (userRepository.findByEmailId(request.getEmailId()).isPresent()) {
//            throw new ChallengeSphereException("Email already exists", HttpStatus.CONFLICT, logger);
//        }
//        if (request.getPhone() != null && !request.getPhone().isBlank()
//                && userRepository.findByPhone(request.getPhone()).isPresent()) {
//            throw new ChallengeSphereException("Phone number already exists", HttpStatus.CONFLICT, logger);
//        }
//
//        User user = new User();
//        user.setUsername(request.getUsername());
//        user.setEmailId(request.getEmailId());
//        user.setPhone(request.getPhone());
//        user.setBio(request.getBio());
//        user.setHashedPassword(passwordEncoder.encode(request.getPassword()));
//        user.setCreatedAt(new Timestamp(System.currentTimeMillis()));
//        user.setActive(true);
//        user.setGenuinenessScore(100);
//        user.setChallengesAccepted(0);
//        user.setChallengesCreated(0);
//        user.setFollowers(0);
//        user.setFollowing(0);
//        user.setNotifications(1);
//        user.setPremiumUser(0);
//
//        User saved = userRepository.save(user);
//        return toAuthResponse(saved);
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public AuthResponse login(LoginRequest request) throws ChallengeSphereException {
//        User user = userRepository.findByUsername(request.getUsername())
//                .or(() -> userRepository.findByEmailId(request.getUsername()))
//                .orElseThrow(() -> new ChallengeSphereException("Invalid username/email or password", HttpStatus.UNAUTHORIZED, logger));
//
//        if (!Boolean.TRUE.equals(user.getActive())) {
//            throw new ChallengeSphereException("User account is inactive", HttpStatus.FORBIDDEN, logger);
//        }
//
//        if (!passwordEncoder.matches(request.getPassword(), user.getHashedPassword())) {
//            throw new ChallengeSphereException("Invalid username/email or password", HttpStatus.UNAUTHORIZED, logger);
//        }
//
//        return toAuthResponse(user);
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public AuthResponse refresh(String refreshToken) throws ChallengeSphereException {
//        if (!jwtService.isValidToken(refreshToken) || !jwtService.isRefreshToken(refreshToken)) {
//            throw new ChallengeSphereException("Invalid refresh token", HttpStatus.UNAUTHORIZED, logger);
//        }
//
//        Long userId = jwtService.extractUserId(refreshToken);
//        User user = userRepository.findById(userId)
//                .orElseThrow(() -> new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));
//
//        return toAuthResponse(user);
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public User getCurrentUser() throws ChallengeSphereException {
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
//            throw new ChallengeSphereException("User not authenticated", HttpStatus.UNAUTHORIZED, logger);
//        }
//        return userRepository.findById(principal.getId())
//                .orElseThrow(() -> new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));
//    }
//
//    @Override
//    @Transactional
//    @SuppressWarnings("unchecked")
//    public AuthResponse loginWithGoogle(GoogleAuthRequest request) throws ChallengeSphereException {
//        String tokenInfoUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=" + request.getIdToken();
//        Map<String, String> tokenInfo;
//        try {
//            RestTemplate restTemplate = new RestTemplate();
//            tokenInfo = restTemplate.getForObject(tokenInfoUrl, Map.class);
//        } catch (Exception e) {
//            throw new ChallengeSphereException("Invalid Google ID token", HttpStatus.UNAUTHORIZED, logger);
//        }
//
//        if (tokenInfo == null || tokenInfo.get("email") == null) {
//            throw new ChallengeSphereException("Invalid Google ID token", HttpStatus.UNAUTHORIZED, logger);
//        }
//
//        if (!googleClientId.isBlank() && !googleClientId.equals(tokenInfo.get("aud"))) {
//            throw new ChallengeSphereException("Google token audience mismatch", HttpStatus.UNAUTHORIZED, logger);
//        }
//
//        String email = tokenInfo.get("email");
//        String picture = tokenInfo.get("picture");
//
//        Optional<User> existing = userRepository.findByEmailId(email);
//        User user;
//        if (existing.isPresent()) {
//            user = existing.get();
//            if (!Boolean.TRUE.equals(user.getActive())) {
//                throw new ChallengeSphereException("User account is inactive", HttpStatus.FORBIDDEN, logger);
//            }
//            // Update profile picture if not set
//            if (user.getProfilePicUrl() == null && picture != null) {
//                user.setProfilePicUrl(picture);
//                user = userRepository.save(user);
//            }
//        } else {
//            // Create new user from Google profile
//            String baseUsername = email.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "");
//            String username = baseUsername;
//            int counter = 1;
//            while (userRepository.findByUsername(username).isPresent()) {
//                username = baseUsername + counter++;
//            }
//
//            user = new User();
//            user.setEmailId(email);
//            user.setUsername(username);
//            user.setHashedPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
//            user.setProfilePicUrl(picture);
//            user.setCreatedAt(new Timestamp(System.currentTimeMillis()));
//            user.setActive(true);
//            user.setGenuinenessScore(100);
//            user.setChallengesAccepted(0);
//            user.setChallengesCreated(0);
//            user.setFollowers(0);
//            user.setFollowing(0);
//            user.setNotifications(1);
//            user.setPremiumUser(0);
//            user = userRepository.save(user);
//        }
//
//        return toAuthResponse(user);
//    }
//
//    private AuthResponse toAuthResponse(User user) {
//        Instant issuedAt = Instant.now();
//        String access = jwtService.generateAccessToken(user.getId(), user.getUsername());
//        String refresh = jwtService.generateRefreshToken(user.getId(), user.getUsername());
//
//        return AuthResponse.builder()
//                .accessToken(access)
//                .refreshToken(refresh)
//                .tokenType("Bearer")
//                .expiresIn(jwtService.getAccessTokenExpiryMs())
//                .issuedAt(issuedAt)
//                .expiresAt(issuedAt.plusMillis(jwtService.getAccessTokenExpiryMs()))
//                .username(user.getUsername())
//                .email(user.getEmailId())
//                .userId(String.valueOf(user.getId()))
//                .user(user)
//                .build();
//    }
//}
