package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.api.request.LoginRequest;
import com.ironside.challengesphere.api.response.AuthResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.security.JwtService;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private UserRepository userRepository;
    private JwtService jwtService;
    private AuthTokenStore authTokenStore;
    private PasswordEncoder passwordEncoder;
    private RedisCacheServiceImpl redisCacheService;
    private UserService userService;
    private EmailService emailService;
    private MediaUrlService mediaUrlService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        authTokenStore = mock(AuthTokenStore.class);
        passwordEncoder = mock(PasswordEncoder.class);
        redisCacheService = mock(RedisCacheServiceImpl.class);
        userService = mock(UserService.class);
        emailService = mock(EmailService.class);
        mediaUrlService = mock(MediaUrlService.class);
        authService = new AuthServiceImpl(
                userRepository,
                jwtService,
                authTokenStore,
                passwordEncoder,
                redisCacheService,
                userService,
                emailService,
                mediaUrlService
        );
    }

    @Test
    void passwordLoginAcceptsEmailIdentifiers() throws Exception {
        User user = activePasswordUser();
        LoginRequest request = new LoginRequest();
        request.setUsername("  person@example.com  ");
        request.setPassword("Secret123");

        when(userRepository.findByEmailIdIgnoreCase("person@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Secret123", user.getHashedPassword())).thenReturn(true);
        when(jwtService.generateAccessToken(user.getId(), user.getUsername())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user.getId(), user.getUsername())).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpiryMs()).thenReturn(3_600_000L);
        when(jwtService.getRefreshTokenExpiryMs()).thenReturn(86_400_000L);
        when(mediaUrlService.toReadableMediaUrl("profiles/person.jpg"))
                .thenReturn("https://signed.example/profiles/person.jpg");

        AuthResponse response = authService.login(request);

        assertNotSame(user, response.getUser());
        assertEquals("https://signed.example/profiles/person.jpg", response.getUser().getProfilePicUrl());
        assertEquals("profiles/person.jpg", user.getProfilePicUrl());
        assertEquals("access-token", response.getAccessToken());
        verify(userRepository).findByEmailIdIgnoreCase("person@example.com");
        verifyNoInteractions(userService);
        verify(userRepository).updateLastLoginAt(user.getId());
    }

    @Test
    void passwordLoginDoesNotReadCredentialDataFromTheProfileCache() throws Exception {
        User user = activePasswordUser();
        LoginRequest request = new LoginRequest();
        request.setUsername("person");
        request.setPassword("Secret123");

        when(userRepository.findByUsername("person")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Secret123", user.getHashedPassword())).thenReturn(true);
        when(jwtService.generateAccessToken(user.getId(), user.getUsername())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user.getId(), user.getUsername())).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpiryMs()).thenReturn(3_600_000L);
        when(jwtService.getRefreshTokenExpiryMs()).thenReturn(86_400_000L);

        authService.login(request);

        verify(userRepository).findByUsername("person");
        verifyNoInteractions(userService);
    }

    @Test
    void forgotPasswordStoresTheSameRawTokenThatIsEmailed() {
        User user = activePasswordUser();
        when(userRepository.findByEmailIdIgnoreCase("person@example.com"))
                .thenReturn(Optional.of(user));

        authService.requestPasswordReset("  person@example.com ");

        ArgumentCaptor<String> storedToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> emailedToken = ArgumentCaptor.forClass(String.class);
        verify(authTokenStore).store(
                storedToken.capture(),
                eq("password_reset"),
                eq(user.getId()),
                eq(Duration.ofMinutes(30))
        );
        verify(emailService).sendPasswordResetEmail(eq(user), emailedToken.capture());
        assertEquals(storedToken.getValue(), emailedToken.getValue());
    }

    @Test
    void forgotPasswordDoesNotRevealUnknownAccountsThroughSideEffects() {
        when(userRepository.findByEmailIdIgnoreCase("missing@example.com"))
                .thenReturn(Optional.empty());

        authService.requestPasswordReset("missing@example.com");

        verifyNoInteractions(authTokenStore, emailService);
    }

    @Test
    void malformedGoogleIdTokenReturnsControlledUnauthorizedError() {
        ReflectionTestUtils.setField(authService, "googleSignInEnabled", true);
        ReflectionTestUtils.setField(
                authService,
                "googleClientId",
                "client.apps.googleusercontent.com"
        );
        GoogleAuthRequest request = new GoogleAuthRequest();
        request.setIdToken("not-a-jwt");

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> authService.loginWithGoogle(request)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertEquals("Invalid Google ID token", error.getResponseMessage());
    }

    @Test
    void resetPasswordPersistsHashAndRevokesExistingSessions() throws Exception {
        User user = activePasswordUser();
        when(authTokenStore.findUserId("reset-token", "password_reset"))
                .thenReturn(Optional.of(user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewSecret123")).thenReturn("$2b$new-hash");

        authService.resetPassword("reset-token", "NewSecret123", "NewSecret123");

        assertEquals("$2b$new-hash", user.getHashedPassword());
        verify(userRepository).save(user);
        verify(authTokenStore).revokeAllForUser(user.getId());
        verify(redisCacheService).invalidateUserProfile(user.getId());
    }

    private User activePasswordUser() {
        User user = new User();
        user.setId(42L);
        user.setUsername("person");
        user.setEmailId("person@example.com");
        user.setActive(true);
        user.setHashedPassword("$2b$valid-bcrypt-shaped-value");
        user.setProfilePicUrl("profiles/person.jpg");
        return user;
    }
}
