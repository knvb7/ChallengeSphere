package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.service.ContentModerationService;
import com.ironside.challengesphere.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModerationEnforcementServiceTest {

    private static final String BUCKET = "test-bucket";

    @Test
    void deletesOwnedFlaggedImageAndCreatesWarningWithoutBanningUser() throws Exception {
        MediaUrlService mediaUrlService = mock(MediaUrlService.class);
        NotificationService notificationService = mock(NotificationService.class);
        ModeratedMediaObjectStore objectStore = mock(ModeratedMediaObjectStore.class);
        ModerationEnforcementService service = new ModerationEnforcementService(
                mediaUrlService, notificationService, objectStore, BUCKET);
        String key = "submissions/user_42/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
        ContentModerationService.Result result = resultFor(key);
        when(mediaUrlService.toStoredMediaUrls(List.of(key))).thenReturn(List.of(key));

        service.removeFlaggedMediaAndWarn(42L, result);

        verify(objectStore).delete(BUCKET, key);
        verify(notificationService).createNotification(
                42L,
                "CONTENT_WARNING",
                "We removed an image because it violated ChallengeSphere's content policy. Please keep future uploads appropriate.",
                "{\"reason\":\"CONTENT_POLICY\",\"removedImageCount\":1}");
    }

    @Test
    void refusesToDeleteAnotherUsersImage() {
        MediaUrlService mediaUrlService = mock(MediaUrlService.class);
        NotificationService notificationService = mock(NotificationService.class);
        ModeratedMediaObjectStore objectStore = mock(ModeratedMediaObjectStore.class);
        ModerationEnforcementService service = new ModerationEnforcementService(
                mediaUrlService, notificationService, objectStore, BUCKET);
        String key = "submissions/user_99/SUBMISSION_PHOTO/1752148800000_123e4567-e89b-12d3-a456-426614174000.jpg";
        ContentModerationService.Result result = resultFor(key);
        when(mediaUrlService.toStoredMediaUrls(List.of(key))).thenReturn(List.of(key));

        assertThrows(ChallengeSphereException.class,
                () -> service.removeFlaggedMediaAndWarn(42L, result));
        verifyNoInteractions(objectStore);
        verifyNoInteractions(notificationService);
    }

    private static ContentModerationService.Result resultFor(String key) {
        return new ContentModerationService.Result(
                ContentModerationService.Verdict.BLOCK_AND_BAN,
                List.of(new ContentModerationService.Finding(
                        key, "Explicit Nudity", "VERY_LIKELY")));
    }
}
