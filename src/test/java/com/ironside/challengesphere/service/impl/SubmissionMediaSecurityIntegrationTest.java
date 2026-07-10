package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.CreateSubmissionRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.util.MediaTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubmissionMediaSecurityIntegrationTest {

    private static final String FOREIGN_KEY =
            "submissions/user_8/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";

    private SubmissionServiceImpl service;
    private SubmissionRepository submissionRepository;
    private UserRepository userRepository;
    private MediaUrlService mediaUrlService;

    @BeforeEach
    void setUp() {
        service = new SubmissionServiceImpl();
        submissionRepository = mock(SubmissionRepository.class);
        userRepository = mock(UserRepository.class);
        mediaUrlService = mock(MediaUrlService.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", new SubmissionMediaKeyPolicy());
        ReflectionTestUtils.setField(service, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "mediaUrlService", mediaUrlService);
    }

    @Test
    void createRejectsForeignMediaBeforeAnySideEffect() {
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setChallengeId(3L);
        request.setDayNumber(1);
        request.setDescription("proof");
        request.setMediaUrls(List.of(FOREIGN_KEY));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.createSubmission(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(submissionRepository, mediaUrlService);
    }

    @Test
    void reuploadRejectsForeignMediaBeforeAnySideEffect() {
        CreateSubmissionRequest request = new CreateSubmissionRequest();
        request.setUserId(7L);
        request.setDescription("replacement");
        request.setMediaUrls(List.of(FOREIGN_KEY));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.reuploadSubmission(99L, request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(submissionRepository, mediaUrlService);
    }

    @Test
    void generatePresignedUrlsDelegatesKeyCreationToPolicy() throws Exception {
        SubmissionMediaKeyPolicy keyPolicy = mock(SubmissionMediaKeyPolicy.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", keyPolicy);
        when(userRepository.existsById(7L)).thenReturn(true);

        GeneratePresignedUrlsRequest.MediaFileInfo mediaFile = jpegMediaFile();
        GeneratePresignedUrlsRequest request = new GeneratePresignedUrlsRequest();
        request.setUserId(7L);
        request.setMediaFiles(List.of(mediaFile));
        when(keyPolicy.createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class)))
                .thenThrow(new ChallengeSphereException("stop before signing", HttpStatus.BAD_REQUEST, null));

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generatePresignedUrls(request));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatus());
        assertEquals("Failed to generate presigned URL for file: proof.jpg", error.getResponseMessage());

        verify(keyPolicy).createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class));
        verifyNoInteractions(mediaUrlService);
    }

    @Test
    void uploadSubmissionMediaDelegatesKeyCreationToPolicy() throws Exception {
        SubmissionMediaKeyPolicy keyPolicy = mock(SubmissionMediaKeyPolicy.class);
        ReflectionTestUtils.setField(service, "submissionMediaKeyPolicy", keyPolicy);
        when(userRepository.existsById(7L)).thenReturn(true);
        when(keyPolicy.createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class)))
                .thenThrow(new ChallengeSphereException("stop before upload", HttpStatus.BAD_REQUEST, null));

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", "image/jpeg", new byte[]{1});
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.uploadSubmissionMedia(
                        7L,
                        file,
                        MediaTypes.SUBMISSION_PHOTO,
                        GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("stop before upload", error.getResponseMessage());

        verify(keyPolicy).createKey(
                eq(7L),
                eq(MediaTypes.SUBMISSION_PHOTO),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG),
                anyLong(),
                any(UUID.class));
        verifyNoInteractions(mediaUrlService);
    }

    private GeneratePresignedUrlsRequest.MediaFileInfo jpegMediaFile() {
        GeneratePresignedUrlsRequest.MediaFileInfo mediaFile =
                new GeneratePresignedUrlsRequest.MediaFileInfo();
        mediaFile.setFileName("proof.jpg");
        mediaFile.setMediaType(MediaTypes.SUBMISSION_PHOTO);
        mediaFile.setContentType(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG);
        return mediaFile;
    }
}
