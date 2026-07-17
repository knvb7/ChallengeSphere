package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.IssuedUploadService;
import com.ironside.challengesphere.security.UploadQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SupportServiceDisabledTest {

    private UserRepository userRepository;
    private UploadQuotaService uploadQuotaService;
    private IssuedUploadService issuedUploadService;
    private SupportServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        uploadQuotaService = mock(UploadQuotaService.class);
        issuedUploadService = mock(IssuedUploadService.class);
        service = new SupportServiceImpl();
    }

    @Test
    void supportTicketEmailPathIsGone() {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(7L, new CreateSupportTicketRequest())
        );

        assertEquals(HttpStatus.GONE, error.getStatus());
        verifyNoInteractions(userRepository, uploadQuotaService, issuedUploadService);
    }

    @Test
    void supportScreenshotUploadPathIsGone() {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generatePresignedUrls(7L, new GeneratePresignedUrlsRequest())
        );

        assertEquals(HttpStatus.GONE, error.getStatus());
        verifyNoInteractions(userRepository, uploadQuotaService, issuedUploadService);
    }
}
