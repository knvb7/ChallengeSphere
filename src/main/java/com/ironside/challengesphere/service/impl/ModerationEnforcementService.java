package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.util.NotificationType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ModerationEnforcementService {

    private static final Logger logger = LogManager.getLogger(ModerationEnforcementService.class);

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif");

    private final MediaUrlService mediaUrlService;
    private final NotificationService notificationService;

    @Autowired
    public ModerationEnforcementService(
            MediaUrlService mediaUrlService,
            NotificationService notificationService) {
        this.mediaUrlService = mediaUrlService;
        this.notificationService = notificationService;
    }

    // Retained for source compatibility with direct callers while production
    // no longer receives or invokes an S3 deletion client.
    ModerationEnforcementService(
            MediaUrlService mediaUrlService,
            NotificationService notificationService,
            ModeratedMediaObjectStore ignoredObjectStore,
            String ignoredBucketName) {
        this(mediaUrlService, notificationService);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void removeFlaggedMediaAndWarn(
            String userId,
            ContentModerationService.Result result) throws ChallengeSphereException {
        if (userId == null || result == null || result.findings() == null) {
            throw new ChallengeSphereException(
                    "Invalid moderation enforcement request",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger);
        }

        List<String> findingUrls = result.findings().stream()
                .map(ContentModerationService.Finding::mediaUrl)
                .toList();
        LinkedHashSet<String> objectKeys = new LinkedHashSet<>(mediaUrlService.toStoredMediaUrls(findingUrls));
        if (objectKeys.isEmpty()) {
            throw new ChallengeSphereException(
                    "Moderation result did not identify an image",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger);
        }

        for (String objectKey : objectKeys) {
            requireOwnedUserImage(userId, objectKey);
        }

        notificationService.createNotification(
                userId,
                NotificationType.CONTENT_WARNING.name(),
                "We hid your submission because an image violated ChallengeSphere's content policy. Please keep future uploads appropriate.",
                "{\"reason\":\"CONTENT_POLICY\",\"flaggedImageCount\":" + objectKeys.size() + "}");
        logger.warn(
                "Hid submission with {} moderated image(s), retained private media, and warned userId={}",
                objectKeys.size(),
                userId);
    }

    private void requireOwnedUserImage(String userId, String objectKey) throws ChallengeSphereException {
        String expectedUser = "user_" + userId + "/";
        boolean ownedNamespace = objectKey.startsWith("submissions/" + expectedUser)
                || objectKey.startsWith("profiles/" + expectedUser)
                || objectKey.startsWith("challenges/" + expectedUser)
                || objectKey.startsWith("support/" + expectedUser);
        String lower = objectKey.toLowerCase(Locale.ROOT);
        boolean image = IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!ownedNamespace || !image || objectKey.contains("..")) {
            throw new ChallengeSphereException(
                    "Rejected moderation media outside the user's image namespace",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger);
        }
    }
}
