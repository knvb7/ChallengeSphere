package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.IssuedUploadService;
import com.ironside.challengesphere.security.UploadQuotaService;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.util.MediaTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ProfilePhotoService {
    private static final Logger logger = LogManager.getLogger(ProfilePhotoService.class);
    private static final long UPLOAD_URL_TTL_SECONDS = 3600L;
    private static final Pattern PROFILE_OBJECT_KEY = Pattern.compile(
            "^profiles/user_(\\d+)/\\d{10,17}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(?:jpg|png|gif)$"
    );

    private final UserRepository userRepository;
    private final MediaUrlService mediaUrlService;
    private final ContentModerationService contentModerationService;
    private final UploadQuotaService uploadQuotaService;
    private final IssuedUploadService issuedUploadService;
    private final ModerationEnforcementService moderationEnforcementService;

    public ProfilePhotoService(
            UserRepository userRepository,
            MediaUrlService mediaUrlService,
            ContentModerationService contentModerationService,
            UploadQuotaService uploadQuotaService,
            IssuedUploadService issuedUploadService,
            ModerationEnforcementService moderationEnforcementService) {
        this.userRepository = userRepository;
        this.mediaUrlService = mediaUrlService;
        this.contentModerationService = contentModerationService;
        this.uploadQuotaService = uploadQuotaService;
        this.issuedUploadService = issuedUploadService;
        this.moderationEnforcementService = moderationEnforcementService;
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

        uploadQuotaService.reserve(userId, List.of(file));

        String extension = extensionFor(file.getContentType());
        String objectName = "profiles/user_%d/%d_%s%s".formatted(
                userId,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                extension
        );
        issuedUploadService.issue(
                userId, objectName, IssuedUploadService.Purpose.PROFILE_PHOTO);
        String uploadUrl = mediaUrlService.createUploadUrl(
                objectName,
                UPLOAD_URL_TTL_SECONDS,
                file.getContentType(),
                file.getFileSizeBytes()
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

    public String validateUploadedObjectKey(Long userId, String objectKey)
            throws ChallengeSphereException {
        String normalizedKey = objectKey == null ? "" : objectKey.trim();
        var matcher = PROFILE_OBJECT_KEY.matcher(normalizedKey);
        if (userId == null
                || !matcher.matches()
                || !Long.toString(userId).equals(matcher.group(1))) {
            throw new ChallengeSphereException(
                    "Invalid profile photo upload key",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        issuedUploadService.consume(
                userId, List.of(normalizedKey), IssuedUploadService.Purpose.PROFILE_PHOTO);

        ContentModerationService.Result moderation;
        try {
            moderation = contentModerationService.scanStrict(List.of(normalizedKey));
        } catch (Exception e) {
            logger.error("Profile photo moderation failed closed for userId={}: {}",
                    userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Profile photo could not be verified for safety. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger,
                    e
            );
        }
        if (moderation == null || moderation.verdict() != ContentModerationService.Verdict.CLEAN) {
            if (moderation != null) {
                moderationEnforcementService.removeFlaggedMediaAndWarn(userId, moderation);
            }
            throw new ChallengeSphereException(
                    "Profile photo removed for violating the content policy. A warning was sent to your notifications.",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return normalizedKey;
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
