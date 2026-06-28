package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.util.MediaTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfilePhotoServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MediaUrlService mediaUrlService;

    private ProfilePhotoService service;

    @BeforeEach
    void setUp() {
        service = new ProfilePhotoService(userRepository, mediaUrlService);
    }

    @Test
    void createsProfileScopedKeyForAuthenticatedImageUpload() throws Exception {
        when(userRepository.existsById(7L)).thenReturn(true);
        when(mediaUrlService.createUploadUrl(
                anyString(),
                eq(3600L),
                eq(GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG)
        )).thenReturn("https://upload.example/signed");

        PresignedUrlsResponse response = service.generateUpload(
                7L,
                mediaFile(
                        "avatar.jpg",
                        MediaTypes.PROFILE_PHOTO,
                        GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG
                )
        );

        assertTrue(response.isSuccess());
        assertEquals(1, response.getPresignedUrls().size());
        PresignedUrlsResponse.PresignedUrlInfo result = response.getPresignedUrls().get(0);
        assertTrue(result.getFileKey().startsWith("profiles/user_7/"));
        assertTrue(result.getFileKey().endsWith(".jpg"));
        assertEquals("PROFILE_PHOTO", result.getFileType());
        assertEquals("https://upload.example/signed", result.getPresignedUrl());
    }

    @Test
    void rejectsVideoContentForProfilePhoto() {
        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generateUpload(
                        7L,
                        mediaFile(
                                "avatar.mp4",
                                MediaTypes.PROFILE_PHOTO,
                                GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4
                        )
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(userRepository, mediaUrlService);
    }

    @Test
    void rejectsMissingUserBeforeSigning() {
        when(userRepository.existsById(404L)).thenReturn(false);

        ChallengeSphereException error = assertThrows(
                ChallengeSphereException.class,
                () -> service.generateUpload(
                        404L,
                        mediaFile(
                                "avatar.png",
                                MediaTypes.PROFILE_PHOTO,
                                GeneratePresignedUrlsRequest.ContentType.IMAGE_PNG
                        )
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verifyNoInteractions(mediaUrlService);
    }

    private GeneratePresignedUrlsRequest.MediaFileInfo mediaFile(
            String fileName,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType
    ) {
        GeneratePresignedUrlsRequest.MediaFileInfo file = new GeneratePresignedUrlsRequest.MediaFileInfo();
        file.setFileName(fileName);
        file.setMediaType(mediaType);
        file.setContentType(contentType);
        return file;
    }
}
