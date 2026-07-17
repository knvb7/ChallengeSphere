package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateFeatureRequest;
import com.ironside.challengesphere.api.response.FeatureRequestResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.FeatureRequest;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.FeatureRequestRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.RateLimitStore;
import com.ironside.challengesphere.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRequestServiceImplTest {

    private FeatureRequestRepository repository;
    private UserRepository userRepository;
    private RateLimitStore rateLimitStore;
    private EmailService emailService;
    private FeatureRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(FeatureRequestRepository.class);
        userRepository = mock(UserRepository.class);
        rateLimitStore = mock(RateLimitStore.class);
        emailService = mock(EmailService.class);
        service = new FeatureRequestServiceImpl(repository, userRepository, rateLimitStore, emailService);
    }

    @Test
    void createFeatureRequestUsesAuthenticatedUserAndStoresTrimmedMessage() throws Exception {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user()));
        when(rateLimitStore.consume(anyString(), eq(5), eq(Duration.ofDays(1))))
                .thenReturn(new RateLimitStore.Decision(true, 4, 0));
        when(repository.save(any(FeatureRequest.class))).thenAnswer(invocation -> {
            FeatureRequest saved = invocation.getArgument(0);
            saved.setId(7L);
            saved.setCreatedAt(Timestamp.valueOf("2026-07-11 12:00:00"));
            return saved;
        });

        FeatureRequestResponse response = service.createFeatureRequest(
                42L,
                request(CreateFeatureRequest.Category.CHALLENGES, "  Add team challenges  ")
        );

        ArgumentCaptor<FeatureRequest> captor = ArgumentCaptor.forClass(FeatureRequest.class);
        verify(repository).save(captor.capture());
        assertEquals(42L, captor.getValue().getUserId());
        assertEquals("Add team challenges", captor.getValue().getMessage());
        assertEquals("CHALLENGES", captor.getValue().getCategory());
        assertEquals("NEW", response.getStatus());
        assertEquals(7L, response.getId());
        verify(emailService).sendFeatureRequestEmail(
                eq("[ChallengeSphere Feature Request] CHALLENGES from @pranjal"),
                org.mockito.ArgumentMatchers.contains("Add team challenges")
        );
    }

    @Test
    void rejectsMessageShorterThanTenCharactersAfterTrimming() {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createFeatureRequest(
                        42L,
                        request(CreateFeatureRequest.Category.OTHER, "   short   ")
                )
        );

        assertEquals("Feature request must be between 10 and 300 characters", error.getResponseMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsMessageLongerThanThreeHundredCharacters() {
        assertThrows(
                ChallengeSphereException.class,
                () -> service.createFeatureRequest(
                        42L,
                        request(CreateFeatureRequest.Category.OTHER, "x".repeat(301))
                )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUnknownUser() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createFeatureRequest(
                        42L,
                        request(CreateFeatureRequest.Category.COMMUNITY, "Add community circles")
                )
        );

        assertEquals("User not found", error.getResponseMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsRequestsAfterDailyLimit() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user()));
        when(rateLimitStore.consume(anyString(), eq(5), eq(Duration.ofDays(1))))
                .thenReturn(new RateLimitStore.Decision(false, 0, 120));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createFeatureRequest(
                        42L,
                        request(CreateFeatureRequest.Category.APP_EXPERIENCE, "Add smoother navigation")
                )
        );

        assertEquals("Daily feature request limit reached", error.getResponseMessage());
        verify(repository, never()).save(any());
    }

    private CreateFeatureRequest request(CreateFeatureRequest.Category category, String message) {
        CreateFeatureRequest request = new CreateFeatureRequest();
        request.setCategory(category);
        request.setMessage(message);
        request.setAppVersion("1.0.0");
        request.setPlatform("android");
        request.setDeviceInfo("Android 16");
        return request;
    }

    private User user() {
        User user = new User();
        user.setId(42L);
        user.setUsername("pranjal");
        user.setEmailId("pranjal@example.com");
        return user;
    }
}
