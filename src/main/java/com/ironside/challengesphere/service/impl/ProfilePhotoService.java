package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.util.MediaTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProfilePhotoService {
    private static final Logger logger = LogManager.getLogger(ProfilePhotoService.class);
    private static final long UPLOAD_URL_TTL_SECONDS = 3600L;

    private final UserRepository userRepository;
    private final MediaUrlService mediaUrlService;

    public ProfilePhotoService(UserRepository userRepository, MediaUrlService mediaUrlService) {
        this.userRepository = userRepository;
        this.mediaUrlService = mediaUrlService;
    }

    public PresignedUrlsResponse generateUpload(
            Long userId,
            GeneratePresignedUrlsRequest.MediaFileInfo file
    ) throws ChallengeSphereException {
        validateProfilePhoto(file);

        if (userId == null || !userRepository.existsById(userId)) {
            throw new ChallengeSphereException(
                    "User not found with id: " + userId,
                    HttpStatus.NOT_FOUND,
                    logger
            );
        }

        String extension = extensionFor(file.getContentType());
        String objectName = "profiles/user_%d/%d_%s%s".formatted(
                userId,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                extension
        );
        String uploadUrl = mediaUrlService.createUploadUrl(
                objectName,
                UPLOAD_URL_TTL_SECONDS,
                file.getContentType()
        );

        PresignedUrlsResponse.PresignedUrlInfo upload = new PresignedUrlsResponse.PresignedUrlInfo(
                file.getFileName(),
                MediaTypes.PROFILE_PHOTO.name(),
                file.getContentType().getMimeType(),
                uploadUrl,
                objectName,
                UPLOAD_URL_TTL_SECONDS
        );
        return PresignedUrlsResponse.success(userId, List.of(upload));
    }

    private void validateProfilePhoto(GeneratePresignedUrlsRequest.MediaFileInfo file)
            throws ChallengeSphereException {
        if (file == null
                || file.getMediaType() != MediaTypes.PROFILE_PHOTO
                || file.getContentType() == null
                || file.getContentType() == GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4) {
            throw new ChallengeSphereException(
                    "Unsupported profile photo media type",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        if (file.getFileName() == null || file.getFileName().isBlank()) {
            throw new ChallengeSphereException(
                    "File name is required",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
    }

    private String extensionFor(GeneratePresignedUrlsRequest.ContentType contentType) {
        return switch (contentType) {
            case IMAGE_PNG -> ".png";
            case IMAGE_GIF -> ".gif";
            case IMAGE_JPEG -> ".jpg";
            case VIDEO_MP4 -> throw new IllegalArgumentException("Video is not a profile photo");
        };
    }
}
