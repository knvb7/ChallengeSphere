package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSupportTicketRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupportServiceDisabledTest {

    private SupportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupportServiceImpl();
    }

    @Test
    void disabledSupportServiceHasNoNonStaticDependencies() {
        long nonStaticFieldCount = Arrays.stream(SupportServiceImpl.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .count();

        assertEquals(0, nonStaticFieldCount);
    }

    @Test
    void supportTicketEmailPathIsGone() {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createTicket(7L, new CreateSupportTicketRequest())
        );

        assertEquals(HttpStatus.GONE, error.getStatus());
    }

    @Test
    void supportScreenshotUploadPathIsGone() {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generatePresignedUrls(7L, new GeneratePresignedUrlsRequest())
        );

        assertEquals(HttpStatus.GONE, error.getStatus());
    }
}
