package com.ironside.challengesphere.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.security.JwtService;
import com.ironside.challengesphere.service.AuthService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LogManager.getLogger(AuthServiceImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final HttpClient GOOGLE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private static final URI GOOGLE_USERINFO_URI = URI.create("https://www.googleapis.com/oauth2/v3/userinfo");

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthTokenStore authTokenStore;
    private final PasswordEncoder passwordEncoder;

    @Value("${google.client.id:}")
    private String googleClientId;

    @Value("${google.signin.enabled:false}")
    private boolean googleSignInEnabled;

    @Value("${jwt.access-token.expiration:3600000}")
    private long accessTokenExpiryMs;

    public AuthServiceImpl(UserRepository userRepository,
                           JwtService jwtService,
                           AuthTokenStore authTokenStore,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authTokenStore = authTokenStore;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public AuthResponse loginWithGoogle(GoogleAuthRequest request) throws ChallengeSphereException {
        if (!googleSignInEnabled) {
            throw new ChallengeSphereException("Google Sign-In is currently disabled",
                    HttpStatus.SERVICE_UNAVAILABLE, logger);
        }
        List<String> allowedAudiences = getAllowedGoogleAudiences();
        if (allowedAudiences.isEmpty()) {
            throw new ChallengeSphereException("Google Sign-In is not configured on this server",
                    HttpStatus.SERVICE_UNAVAILABLE, logger);
        }

        GoogleProfile profile = verifyGoogleCredentials(request, allowedAudiences);

        String googleId = profile.googleId();
        String email = profile.email();
        String name = profile.name();
        String pictureUrl = profile.pictureUrl();

        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(() -> userRepository.findByEmailId(email)
                        .map(existing -> linkGoogleId(existing, googleId, pictureUrl))
                        .orElseGet(() -> createGoogleUser(email, googleId, name, pictureUrl)));

        logger.info("Google sign-in success for userId={} email={}", user.getId(), email);
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse register(UserRegistrationRequest request) throws ChallengeSphereException {
        throw new ChallengeSphereException("Use UserService.registerUser for registration",
                HttpStatus.NOT_IMPLEMENTED, logger);
    }

    @Override
    public AuthResponse login(LoginRequest request) throws ChallengeSphereException {
        User user = userRepository.findByUsername(request.getUsername())
                .or(() -> userRepository.findByEmailId(request.getUsername()))
                .orElseThrow(() -> new ChallengeSphereException("Invalid username or password",
                        HttpStatus.UNAUTHORIZED, logger));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new ChallengeSphereException("User account is disabled",
                    HttpStatus.FORBIDDEN, logger);
        }
        if (!passwordMatches(request.getPassword(), user.getHashedPassword())) {
            throw new ChallengeSphereException("Invalid username or password",
                    HttpStatus.UNAUTHORIZED, logger);
        }

        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        userRepository.updateLastLoginAt(user.getId());
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse refresh(String refreshToken) throws ChallengeSphereException {
        if (!jwtService.isValidToken(refreshToken) || !jwtService.isRefreshToken(refreshToken)) {
            throw new ChallengeSphereException("Invalid or expired refresh token",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        Long userId = jwtService.extractUserId(refreshToken);
        if (!authTokenStore.exists(refreshToken, "refresh", userId)) {
            throw new ChallengeSphereException("Invalid or expired refresh token",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException("User not found",
                        HttpStatus.UNAUTHORIZED, logger));
        authTokenStore.revoke(refreshToken, "refresh");
        return buildAuthResponse(user);
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        authTokenStore.revoke(accessToken, "access");
        authTokenStore.revoke(refreshToken, "refresh");
    }

    @Override
    public User getCurrentUser(String accessToken) throws ChallengeSphereException {
        if (!jwtService.isValidAccessToken(accessToken)) {
            throw new ChallengeSphereException("Invalid or expired access token",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        Long userId = jwtService.extractUserId(accessToken);
        if (!authTokenStore.exists(accessToken, "access", userId)) {
            throw new ChallengeSphereException("Invalid or expired access token",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException("User not found",
                        HttpStatus.UNAUTHORIZED, logger));
    }

    @Override
    public User getCurrentUser() throws ChallengeSphereException {
        throw new ChallengeSphereException("Not implemented", HttpStatus.NOT_IMPLEMENTED, logger);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword, String confirmPassword)
            throws ChallengeSphereException {
        if (userId == null) {
            throw new ChallengeSphereException("Authentication required", HttpStatus.UNAUTHORIZED, logger);
        }
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new ChallengeSphereException("New passwords do not match", HttpStatus.BAD_REQUEST, logger);
        }
        validatePasswordPolicy(newPassword);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));
        if (user.getHashedPassword() == null || user.getHashedPassword().isBlank()) {
            throw new ChallengeSphereException("Password login is not configured for this account",
                    HttpStatus.BAD_REQUEST, logger);
        }
        if (!passwordEncoder.matches(currentPassword, user.getHashedPassword())) {
            throw new ChallengeSphereException("Current password is incorrect", HttpStatus.UNAUTHORIZED, logger);
        }
        if (passwordEncoder.matches(newPassword, user.getHashedPassword())) {
            throw new ChallengeSphereException("New password must be different from the current password",
                    HttpStatus.BAD_REQUEST, logger);
        }

        user.setHashedPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        authTokenStore.revokeAllForUser(userId);
        logger.info("Password changed and sessions revoked for userId={}", userId);
    }

    // --- helpers ---

    private GoogleProfile verifyGoogleCredentials(GoogleAuthRequest request, List<String> allowedAudiences)
            throws ChallengeSphereException {
        if (request == null) {
            throw new ChallengeSphereException("Google credentials are required", HttpStatus.BAD_REQUEST, logger);
        }

        if (StringUtils.hasText(request.getIdToken())) {
            return verifyGoogleIdToken(request.getIdToken(), allowedAudiences);
        }

        if (StringUtils.hasText(request.getAccessToken())) {
            return verifyGoogleAccessToken(request.getAccessToken(), allowedAudiences);
        }

        throw new ChallengeSphereException("Google token is required", HttpStatus.BAD_REQUEST, logger);
    }

    private GoogleProfile verifyGoogleIdToken(String idTokenString, List<String> allowedAudiences)
            throws ChallengeSphereException {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(allowedAudiences)
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new ChallengeSphereException("Invalid Google ID token",
                        HttpStatus.UNAUTHORIZED, logger);
            }
            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            if (!StringUtils.hasText(email)) {
                throw new ChallengeSphereException("Google account email is required",
                        HttpStatus.UNAUTHORIZED, logger);
            }
            Object emailVerified = payload.get("email_verified");
            if (emailVerified != null && !Boolean.parseBoolean(String.valueOf(emailVerified))) {
                throw new ChallengeSphereException("Google account email is not verified",
                        HttpStatus.UNAUTHORIZED, logger);
            }
            return new GoogleProfile(
                    payload.getSubject(),
                    email,
                    (String) payload.get("name"),
                    (String) payload.get("picture")
            );
        } catch (GeneralSecurityException | IOException e) {
            logger.error("Google token verification failed: {}", e.getMessage());
            throw new ChallengeSphereException("Google token verification failed",
                    HttpStatus.UNAUTHORIZED, logger);
        }
    }

    private GoogleProfile verifyGoogleAccessToken(String accessToken, List<String> allowedAudiences)
            throws ChallengeSphereException {
        try {
            Map<String, Object> tokenInfo = getGoogleTokenInfo(accessToken);
            String audience = firstText(tokenInfo.get("aud"), tokenInfo.get("audience"));
            String authorizedParty = firstText(tokenInfo.get("azp"), tokenInfo.get("issued_to"));
            boolean audienceAllowed = StringUtils.hasText(audience) && allowedAudiences.contains(audience);
            boolean authorizedPartyAllowed = StringUtils.hasText(authorizedParty) && allowedAudiences.contains(authorizedParty);
            if (!audienceAllowed && !authorizedPartyAllowed) {
                logger.warn("Rejected Google access token with audience={} authorizedParty={}", audience, authorizedParty);
                throw new ChallengeSphereException("Invalid Google access token audience",
                        HttpStatus.UNAUTHORIZED, logger);
            }

            Map<String, Object> userInfo = getGoogleUserInfo(accessToken);
            String googleId = firstText(userInfo.get("sub"), tokenInfo.get("sub"), tokenInfo.get("user_id"));
            String email = firstText(userInfo.get("email"), tokenInfo.get("email"));
            if (!StringUtils.hasText(googleId) || !StringUtils.hasText(email)) {
                throw new ChallengeSphereException("Google account details are incomplete",
                        HttpStatus.UNAUTHORIZED, logger);
            }

            String verified = firstText(userInfo.get("email_verified"), tokenInfo.get("email_verified"), tokenInfo.get("verified_email"));
            if (StringUtils.hasText(verified) && !Boolean.parseBoolean(verified)) {
                throw new ChallengeSphereException("Google account email is not verified",
                        HttpStatus.UNAUTHORIZED, logger);
            }

            return new GoogleProfile(
                    googleId,
                    email,
                    stringValue(userInfo.get("name")),
                    stringValue(userInfo.get("picture"))
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Google access token verification failed: {}", e.getMessage());
            throw new ChallengeSphereException("Google token verification failed",
                    HttpStatus.UNAUTHORIZED, logger);
        }
    }

    private Map<String, Object> getGoogleTokenInfo(String accessToken) throws IOException, InterruptedException {
        String encodedToken = URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://oauth2.googleapis.com/tokeninfo?access_token=" + encodedToken))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        return executeGoogleJsonRequest(request);
    }

    private Map<String, Object> getGoogleUserInfo(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(GOOGLE_USERINFO_URI)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return executeGoogleJsonRequest(request);
    }

    private Map<String, Object> executeGoogleJsonRequest(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = GOOGLE_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warn("Google token endpoint returned status={} body={}", response.statusCode(), response.body());
            throw new IOException("Google token endpoint returned " + response.statusCode());
        }
        return OBJECT_MAPPER.readValue(response.body(), JSON_MAP);
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> getAllowedGoogleAudiences() {
        if (googleClientId == null || googleClientId.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(googleClientId.split(","))
                .map(String::trim)
                .filter(clientId -> !clientId.isBlank())
                .collect(Collectors.toList());
    }

    private User linkGoogleId(User user, String googleId, String pictureUrl) {
        user.setGoogleId(googleId);
        if (user.getProfilePicUrl() == null && pictureUrl != null) {
            user.setProfilePicUrl(pictureUrl);
        }
        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        return userRepository.save(user);
    }

    private User createGoogleUser(String email, String googleId, String name, String pictureUrl) {
        User user = new User();
        user.setEmailId(email);
        user.setGoogleId(googleId);
        user.setProfilePicUrl(pictureUrl);
        user.setUsername(generateUsername(email, name));
        user.setHashedPassword(null);
        user.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        user.setGenuinenessScore(100);
        user.setActive(true);
        user.setChallengesCreated(0);
        user.setChallengesAccepted(0);
        user.setFollowers(0);
        user.setFollowing(0);
        user.setPremiumUser(0);
        user.setNotifications(1);
        logger.info("Creating new Google user email={}", email);
        return userRepository.save(user);
    }

    private String generateUsername(String email, String name) {
        String base = (name != null && !name.isBlank())
                ? name.toLowerCase().replaceAll("[^a-z0-9_]", "_")
                : email.split("@")[0].replaceAll("[^a-z0-9_]", "_");
        base = base.length() > 20 ? base.substring(0, 20) : base;
        String candidate = base;
        // append random suffix until unique
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + "_" + UUID.randomUUID().toString().substring(0, 5);
        }
        return candidate;
    }

    private AuthResponse buildAuthResponse(User user) {
        Instant issuedAt = Instant.now();
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername());
        authTokenStore.store(accessToken, "access", user.getId(), Duration.ofMillis(jwtService.getAccessTokenExpiryMs()));
        authTokenStore.store(refreshToken, "refresh", user.getId(), Duration.ofMillis(jwtService.getRefreshTokenExpiryMs()));
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .username(user.getUsername())
                .email(user.getEmailId())
                .userId(String.valueOf(user.getId()))
                .expiresIn(accessTokenExpiryMs)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusMillis(accessTokenExpiryMs))
                .user(user)
                .build();
    }

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isBlank()) {
            return false;
        }
        if (!isBcryptHash(storedPassword)) {
            logger.warn("Rejecting login for account with non-bcrypt password hash");
            return false;
        }
        return passwordEncoder.matches(rawPassword, storedPassword);
    }

    private boolean isBcryptHash(String storedPassword) {
        return storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$");
    }

    private void validatePasswordPolicy(String password) throws ChallengeSphereException {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new ChallengeSphereException("Password must be between 8 and 128 characters",
                    HttpStatus.BAD_REQUEST, logger);
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new ChallengeSphereException("Password must contain at least one letter and one number",
                    HttpStatus.BAD_REQUEST, logger);
        }
    }

    private record GoogleProfile(String googleId, String email, String name, String pictureUrl) {
    }
}
