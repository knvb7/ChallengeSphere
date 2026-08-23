package com.ironside.challengesphere.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.AppleAuthRequest;
import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.request.UserRegistrationRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.api.response.SelfUserResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.AuthIdentity;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.repository.AuthIdentityRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.security.JwtService;
import com.ironside.challengesphere.service.AuthService;
import com.ironside.challengesphere.service.UserService;
import com.ironside.challengesphere.util.UsernamePolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
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
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
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
    private static final String INVALID_GOOGLE_ID_TOKEN = "Invalid Google ID token";
    private static final String GOOGLE_TOKEN_VERIFICATION_FAILED = "Google token verification failed";
    private static final String PASSWORD_RESET_TOKEN_TYPE = "password_reset";
    private static final Duration PASSWORD_RESET_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration PASSWORD_MUTATION_LEASE = Duration.ofMinutes(1);
    private static final String RESET_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int RESET_CODE_LENGTH = 10;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final AppleSignInService appleSignInService;
    private final JwtService jwtService;
    private final AuthTokenStore authTokenStore;
    private final DynamoOperationalStore operationalStore;
    private final PasswordEncoder passwordEncoder;
    private final RedisCacheServiceImpl redisCacheServiceImpl;
    private final UserService userService;
    private final MediaUrlService mediaUrlService;

    @Value("${google.client.id:}")
    private String googleClientId;

    @Value("${google.signin.enabled:false}")
    private boolean googleSignInEnabled;

    @Value("${jwt.access-token.expiration:3600000}")
    private long accessTokenExpiryMs;

    public AuthServiceImpl(UserRepository userRepository,
                           AuthIdentityRepository authIdentityRepository,
                           AppleSignInService appleSignInService,
                           JwtService jwtService,
                           AuthTokenStore authTokenStore,
                           DynamoOperationalStore operationalStore,
                           PasswordEncoder passwordEncoder,
                           RedisCacheServiceImpl redisCacheServiceImpl,
                           UserService userService,
                           MediaUrlService mediaUrlService) {
        this.userRepository = userRepository;
        this.authIdentityRepository = authIdentityRepository;
        this.appleSignInService = appleSignInService;
        this.jwtService = jwtService;
        this.authTokenStore = authTokenStore;
        this.operationalStore = operationalStore;
        this.passwordEncoder = passwordEncoder;
        this.redisCacheServiceImpl = redisCacheServiceImpl;
        this.userService = userService;
        this.mediaUrlService = mediaUrlService;
    }

    @Override
    @Transactional
    public AuthResponse loginWithApple(AppleAuthRequest request) throws ChallengeSphereException {
        AppleSignInService.VerifiedAppleAccount account = appleSignInService.verifyAndExchange(request);
        Optional<AuthIdentity> existingIdentity = authIdentityRepository.findByProviderAndSubject(
                "APPLE",
                account.subject()
        );

        String displayName = appleDisplayName(request.getFullName());
        TextContentSafetyPolicy.requireAllowed(displayName, "display name");
        Optional<User> emailUser = existingIdentity.isPresent() || !StringUtils.hasText(account.email())
                ? Optional.empty()
                : userRepository.findByEmailIdIgnoreCase(account.email());
        boolean isNewUser = existingIdentity.isEmpty() && emailUser.isEmpty();

        User user;
        if (existingIdentity.isPresent()) {
            user = existingIdentity.get().getUser();
            ensureAccountMayAuthenticate(user);
            user = backfillProviderName(user, displayName);
        } else if (emailUser.isPresent()) {
            user = emailUser.get();
            ensureAccountMayAuthenticate(user);
            user = backfillProviderName(user, displayName);
        } else {
            if (!StringUtils.hasText(account.email())) {
                throw new ChallengeSphereException(
                        "Apple did not share an email for this new account. Remove ChallengeSphere from Apple ID sign-in settings and try again.",
                        HttpStatus.UNAUTHORIZED,
                        logger
                );
            }
            user = createAppleUser(account.email(), displayName);
        }

        saveProviderIdentity(
                user,
                "APPLE",
                account.subject(),
                account.email(),
                account.encryptedRefreshToken()
        );
        user = touchProviderLogin(user);

        logger.info("Apple sign-in success for userId={}", user.getId());
        AuthResponse response = buildAuthResponse(user);
        response.setIsNewUser(isNewUser);
        return response;
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
        TextContentSafetyPolicy.requireAllowed(name, "display name");

        Optional<AuthIdentity> googleIdentity = authIdentityRepository.findByProviderAndSubject(
                "GOOGLE",
                googleId
        );
        Optional<User> googleUser = googleIdentity
                .map(AuthIdentity::getUser)
                .or(() -> userRepository.findByGoogleId(googleId));
        Optional<User> emailUser = googleUser.isPresent()
                ? Optional.empty()
                : userRepository.findByEmailId(email);
        boolean isNewUser = googleUser.isEmpty() && emailUser.isEmpty();

        User user;
        if (googleUser.isPresent()) {
            user = googleUser.get();
            ensureAccountMayAuthenticate(user);
            user = backfillGoogleName(user, profile.name());
        } else if (emailUser.isPresent()) {
            user = emailUser.get();
            ensureAccountMayAuthenticate(user);
            user = linkGoogleId(user, googleId, profile.name(), pictureUrl);
        } else {
            user = createGoogleUser(email, googleId, name, pictureUrl);
        }

        saveProviderIdentity(user, "GOOGLE", googleId, email, null);
        user = touchProviderLogin(user);

        logger.info("Google sign-in success for userId={}", user.getId());
        AuthResponse response = buildAuthResponse(user);
        response.setIsNewUser(isNewUser);
        return response;
    }

    @Override
    public AuthResponse register(UserRegistrationRequest request) throws ChallengeSphereException {
        throw new ChallengeSphereException("Use UserService.registerUser for registration",
                HttpStatus.NOT_IMPLEMENTED, logger);
    }

    @Override
    public AuthResponse login(LoginRequest request) throws ChallengeSphereException {
        String identifier = request.getUsername().trim();
        Optional<User> userLookup = identifier.contains("@")
                ? userRepository.findByEmailIdIgnoreCase(identifier)
                : userRepository.findByUsername(identifier);
        User user = userLookup.orElseThrow(() -> new ChallengeSphereException(
                "Invalid username or password",
                HttpStatus.UNAUTHORIZED,
                logger
        ));

        if (!passwordMatches(request.getPassword(), user.getHashedPassword())) {
            throw new ChallengeSphereException("Invalid username or password",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        ensureAccountMayAuthenticate(user);

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
        String userId = jwtService.extractUserId(refreshToken);
        if (!authTokenStore.exists(refreshToken, "refresh", userId)) {
            throw new ChallengeSphereException("Invalid or expired refresh token",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Invalid or expired refresh token",
                        HttpStatus.UNAUTHORIZED,
                        logger
                ));
        ensureAccountMayAuthenticate(user);
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
        String userId = jwtService.extractUserId(accessToken);
        if (!authTokenStore.exists(accessToken, "access", userId)) {
            throw new ChallengeSphereException("Invalid or expired access token",
                    HttpStatus.UNAUTHORIZED, logger);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException("User not found",
                        HttpStatus.UNAUTHORIZED, logger));
        ensureAccountMayAuthenticate(user);
        return toReadableAuthUser(user);
    }

    @Override
    public User getCurrentUser() throws ChallengeSphereException {
        throw new ChallengeSphereException("Not implemented", HttpStatus.NOT_IMPLEMENTED, logger);
    }

    @Override
    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword, String confirmPassword)
            throws ChallengeSphereException {
        if (userId == null) {
            throw new ChallengeSphereException("Authentication required", HttpStatus.UNAUTHORIZED, logger);
        }
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new ChallengeSphereException("New passwords do not match", HttpStatus.BAD_REQUEST, logger);
        }
        validatePasswordPolicy(newPassword);

        DynamoOperationalStore.Lease lease = acquirePasswordMutationLease(userId);
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "User not found", HttpStatus.NOT_FOUND, logger
                    ));
            if (user.getHashedPassword() == null || user.getHashedPassword().isBlank()) {
                throw new ChallengeSphereException(
                        "Password login is not configured for this account",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            boolean currentPasswordMatches = passwordMatches(
                    currentPassword, user.getHashedPassword()
            );
            boolean requestedPasswordAlreadyApplied = passwordMatches(
                    newPassword, user.getHashedPassword()
            );
            if (!currentPasswordMatches && !requestedPasswordAlreadyApplied) {
                throw new ChallengeSphereException(
                        "Current password is incorrect", HttpStatus.UNAUTHORIZED, logger
                );
            }
            if (currentPasswordMatches && requestedPasswordAlreadyApplied) {
                throw new ChallengeSphereException(
                        "New password must be different from the current password",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }

            // Keep the durable password unchanged unless every existing session
            // has first been revoked. A failed main-table write is then safely
            // retryable with the old password; an ambiguous committed write is
            // recognized by requestedPasswordAlreadyApplied on the next attempt.
            authTokenStore.revokeAllForUser(userId);
            if (!requestedPasswordAlreadyApplied) {
                user.setHashedPassword(passwordEncoder.encode(newPassword));
                userRepository.save(user);
            }
            safeInvalidateUserProfile(userId);
            logger.info("Password changed and sessions revoked for userId={}", userId);
        } finally {
            operationalStore.releaseLease(lease);
        }
    }

    @Override
    @Transactional
    public void resetPassword(String resetCode, String newPassword, String confirmPassword)
            throws ChallengeSphereException {
        String normalizedResetCode = normalizeResetCode(resetCode);
        if (!normalizedResetCode.matches("[A-HJ-NP-Z2-9]{" + RESET_CODE_LENGTH + "}")) {
            throw new ChallengeSphereException("Password reset code is invalid or expired",
                    HttpStatus.BAD_REQUEST, logger);
        }
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new ChallengeSphereException("New passwords do not match", HttpStatus.BAD_REQUEST, logger);
        }
        validatePasswordPolicy(newPassword);

        String userId = resolveResetCodeUser(normalizedResetCode);
        DynamoOperationalStore.Lease lease = acquirePasswordMutationLease(userId);
        try {
            // Re-read after acquiring the per-user lease so two reset requests
            // cannot both pass token validation and race the password write.
            String leasedUserId = resolveResetCodeUser(normalizedResetCode);
            if (!userId.equals(leasedUserId)) {
                throw invalidResetCode();
            }
            User user = userRepository.findById(userId)
                    .orElseThrow(this::invalidResetCode);
            boolean requestedPasswordAlreadyApplied = passwordMatches(
                    newPassword, user.getHashedPassword()
            );

            // Revoke every session while deliberately retaining the reset code
            // as a retry credential until the main-table password write is done.
            authTokenStore.revokeAllForUserExcept(
                    userId,
                    normalizedResetCode,
                    PASSWORD_RESET_TOKEN_TYPE
            );
            if (!requestedPasswordAlreadyApplied) {
                user.setHashedPassword(passwordEncoder.encode(newPassword));
                userRepository.save(user);
            }
            safeInvalidateUserProfile(userId);

            // This is the terminal operation. If anything before it fails, the
            // same code can resume; revocation itself is an atomic Dynamo write.
            authTokenStore.revoke(normalizedResetCode, PASSWORD_RESET_TOKEN_TYPE);
            logger.info("Password reset completed and sessions revoked for userId={}", userId);
        } finally {
            operationalStore.releaseLease(lease);
        }
    }

    // --- helpers ---

    private DynamoOperationalStore.Lease acquirePasswordMutationLease(String userId)
            throws ChallengeSphereException {
        try {
            return operationalStore.tryAcquireLease(
                            "PASSWORD_MUTATION", userId, PASSWORD_MUTATION_LEASE
                    )
                    .orElseThrow(() -> new ChallengeSphereException(
                            "A password update is already in progress. Please try again.",
                            HttpStatus.CONFLICT,
                            logger
                    ));
        } catch (ChallengeSphereException exception) {
            throw exception;
        } catch (RuntimeException unavailable) {
            throw new ChallengeSphereException(
                    "Password service is temporarily unavailable. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger,
                    unavailable
            );
        }
    }

    private String resolveResetCodeUser(String normalizedResetCode)
            throws ChallengeSphereException {
        try {
            return authTokenStore.findUser(
                            normalizedResetCode, PASSWORD_RESET_TOKEN_TYPE
                    )
                    .orElseThrow(this::invalidResetCode);
        } catch (ChallengeSphereException exception) {
            throw exception;
        } catch (RuntimeException unavailable) {
            throw new ChallengeSphereException(
                    "Password reset service is temporarily unavailable. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger,
                    unavailable
            );
        }
    }

    private ChallengeSphereException invalidResetCode() {
        return new ChallengeSphereException(
                "Password reset code is invalid or expired",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }

    private void safeInvalidateUserProfile(String userId) {
        try {
            redisCacheServiceImpl.invalidateUserProfile(userId);
        } catch (RuntimeException unavailable) {
            logger.warn(
                    "User-profile cache invalidation remains pending for userId={}: {}",
                    userId,
                    unavailable.getClass().getSimpleName()
            );
        }
    }

    private String generateResetCode() {
        StringBuilder code = new StringBuilder(RESET_CODE_LENGTH);
        for (int i = 0; i < RESET_CODE_LENGTH; i++) {
            code.append(RESET_CODE_ALPHABET.charAt(
                    SECURE_RANDOM.nextInt(RESET_CODE_ALPHABET.length())
            ));
        }
        return code.toString();
    }

    private String normalizeResetCode(String resetCode) {
        if (resetCode == null) {
            return "";
        }
        return resetCode
                .replaceAll("[\\s-]", "")
                .toUpperCase(Locale.ROOT);
    }

    private GoogleProfile verifyGoogleCredentials(GoogleAuthRequest request, List<String> allowedAudiences)
            throws ChallengeSphereException {
        if (request == null) {
            throw new ChallengeSphereException("Google credentials are required", HttpStatus.BAD_REQUEST, logger);
        }

        if (StringUtils.hasText(request.getIdToken())) {
            try {
                return verifyGoogleIdToken(request.getIdToken(), allowedAudiences);
            } catch (ChallengeSphereException idTokenFailure) {
                boolean canVerifyAccessToken =
                        StringUtils.hasText(request.getAccessToken())
                                && isRetryableGoogleIdTokenFailure(idTokenFailure);
                if (!canVerifyAccessToken) {
                    throw idTokenFailure;
                }

                logger.warn("Google ID token was rejected; verifying the supplied access token instead");
                return verifyGoogleAccessToken(request.getAccessToken(), allowedAudiences);
            }
        }

        if (StringUtils.hasText(request.getAccessToken())) {
            return verifyGoogleAccessToken(request.getAccessToken(), allowedAudiences);
        }

        throw new ChallengeSphereException("Google token is required", HttpStatus.BAD_REQUEST, logger);
    }

    GoogleProfile verifyGoogleIdToken(String idTokenString, List<String> allowedAudiences)
            throws ChallengeSphereException {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(allowedAudiences)
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new ChallengeSphereException(INVALID_GOOGLE_ID_TOKEN,
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
        } catch (IllegalArgumentException e) {
            logger.warn("Rejected malformed Google ID token");
            throw new ChallengeSphereException(INVALID_GOOGLE_ID_TOKEN,
                    HttpStatus.UNAUTHORIZED, logger);
        } catch (GeneralSecurityException | IOException e) {
            logger.error("Google ID token verification failed");
            throw new ChallengeSphereException(GOOGLE_TOKEN_VERIFICATION_FAILED,
                    HttpStatus.UNAUTHORIZED, logger);
        }
    }

    GoogleProfile verifyGoogleAccessToken(String accessToken, List<String> allowedAudiences)
            throws ChallengeSphereException {
        try {
            Map<String, Object> tokenInfo = getGoogleTokenInfo(accessToken);
            String audience = firstText(tokenInfo.get("aud"), tokenInfo.get("audience"));
            String authorizedParty = firstText(tokenInfo.get("azp"), tokenInfo.get("issued_to"));
            boolean audienceAllowed = StringUtils.hasText(audience) && allowedAudiences.contains(audience);
            boolean authorizedPartyAllowed = StringUtils.hasText(authorizedParty) && allowedAudiences.contains(authorizedParty);
            if (!audienceAllowed && !authorizedPartyAllowed) {
                logger.warn("Rejected Google access token with an untrusted audience");
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
            logger.error("Google access token verification failed");
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
            logger.warn("Google token endpoint rejected token with status={}", response.statusCode());
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

    private boolean isRetryableGoogleIdTokenFailure(ChallengeSphereException failure) {
        return INVALID_GOOGLE_ID_TOKEN.equals(failure.getResponseMessage())
                || GOOGLE_TOKEN_VERIFICATION_FAILED.equals(failure.getResponseMessage());
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

    private User backfillGoogleName(User user, String googleName) {
        if (!StringUtils.hasText(user.getName()) && StringUtils.hasText(googleName)) {
            user.setName(normalizeDisplayName(googleName));
            return userRepository.save(user);
        }
        return user;
    }

    private User backfillProviderName(User user, String displayName) {
        if (!StringUtils.hasText(user.getName()) && StringUtils.hasText(displayName)) {
            user.setName(normalizeDisplayName(displayName));
            return userRepository.save(user);
        }
        return user;
    }

    private User linkGoogleId(User user, String googleId, String googleName, String pictureUrl) {
        user.setGoogleId(googleId);
        if (!StringUtils.hasText(user.getName()) && StringUtils.hasText(googleName)) {
            user.setName(normalizeDisplayName(googleName));
        }
        if (user.getProfilePicUrl() == null && pictureUrl != null) {
            user.setProfilePicUrl(pictureUrl);
        }
        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        return userRepository.save(user);
    }

    private User createGoogleUser(String email, String googleId, String googleName, String pictureUrl) {
        User user = new User();
        user.setEmailId(email);
        user.setGoogleId(googleId);
        user.setProfilePicUrl(pictureUrl);
        user.setUsername(generateUsername(email, googleName));
        user.setName(normalizeDisplayName(googleName));
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
        logger.info("Creating new Google-authenticated user");
        return userRepository.save(user);
    }

    private User createAppleUser(String email, String displayName) {
        User user = new User();
        user.setEmailId(email);
        user.setUsername(generateUsername(email, displayName));
        user.setName(normalizeDisplayName(displayName));
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
        logger.info("Creating new Apple-authenticated user");
        return userRepository.save(user);
    }

    private AuthIdentity saveProviderIdentity(
            User user,
            String provider,
            String subject,
            String providerEmail,
            String encryptedRefreshToken) throws ChallengeSphereException {
        Optional<AuthIdentity> bySubject = authIdentityRepository.findByProviderAndSubject(provider, subject);
        Optional<AuthIdentity> byUser = authIdentityRepository.findByUserIdAndProvider(user.getId(), provider);
        AuthIdentity identity = bySubject.orElseGet(() -> byUser.orElseGet(AuthIdentity::new));

        if (identity.getId() != null
                && (!identity.getUser().getId().equals(user.getId())
                || !identity.getSubject().equals(subject))) {
            throw new ChallengeSphereException(
                    "This sign-in identity is already linked to another account",
                    HttpStatus.CONFLICT,
                    logger
            );
        }

        Instant now = Instant.now();
        identity.setUser(user);
        identity.setProvider(provider);
        identity.setSubject(subject);
        if (StringUtils.hasText(providerEmail)) {
            identity.setProviderEmail(providerEmail.trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(encryptedRefreshToken)) {
            identity.setEncryptedRefreshToken(encryptedRefreshToken);
        }
        if (identity.getCreatedAt() == null) {
            identity.setCreatedAt(now);
        }
        identity.setLastUsedAt(now);
        return authIdentityRepository.save(identity);
    }

    private User touchProviderLogin(User user) {
        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        return userRepository.save(user);
    }

    private String appleDisplayName(AppleAuthRequest.FullName fullName) {
        if (fullName == null) {
            return null;
        }
        List<String> nameParts = java.util.stream.Stream.of(
                fullName.getNamePrefix(),
                fullName.getGivenName(),
                fullName.getMiddleName(),
                fullName.getFamilyName(),
                fullName.getNameSuffix()
        ).filter(StringUtils::hasText).map(String::trim).toList();
        if (!nameParts.isEmpty()) {
            return String.join(" ", nameParts);
        }
        return fullName.getNickname();
    }

    private String normalizeDisplayName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return null;
        }
        String normalized = displayName
                .replaceAll("[\\p{Cntrl}<>]", "")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120).trim();
    }

    private String generateUsername(String email, String name) {
        String rawBase = StringUtils.hasText(name) ? name : email.split("@")[0];
        String base = UsernamePolicy.sanitizeGeneratedUsernameBase(rawBase);
        if (base.length() < UsernamePolicy.MIN_LENGTH) {
            base += "_user";
        }
        base = base.substring(0, Math.min(base.length(), UsernamePolicy.MAX_LENGTH));

        String candidate = base;
        while (userRepository.findByUsername(candidate).isPresent()) {
            String suffix = "_" + UUID.randomUUID().toString().substring(0, 5);
            int prefixLength = Math.min(base.length(), UsernamePolicy.MAX_LENGTH - suffix.length());
            candidate = base.substring(0, prefixLength) + suffix;
        }
        return candidate;
    }

    private AuthResponse buildAuthResponse(User user) throws ChallengeSphereException {
        ensureAccountMayAuthenticate(user);
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
                .user(SelfUserResponse.from(toReadableAuthUser(user)))
                .build();
    }

    private void ensureAccountMayAuthenticate(User user) throws ChallengeSphereException {
        if (user != null
                && Boolean.TRUE.equals(user.getActive())
                && user.getBannedAt() == null) {
            return;
        }

        if (user != null && user.getId() != null) {
            authTokenStore.revokeAllForUser(user.getId());
        }
        throw new ChallengeSphereException(
                "User account is disabled",
                HttpStatus.FORBIDDEN,
                logger
        );
    }

    private User toReadableAuthUser(User user) {
        User readableUser = new User();
        BeanUtils.copyProperties(user, readableUser);
        readableUser.setProfilePicUrl(
                mediaUrlService.toReadableProfilePhoto(user.getId(), user.getProfilePicUrl()));
        return readableUser;
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

    record GoogleProfile(String googleId, String email, String name, String pictureUrl) {
    }
}
