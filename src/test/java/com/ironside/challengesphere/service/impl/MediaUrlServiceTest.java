package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.util.GetSignedUrlForS3;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaUrlServiceTest {

    @Test
    void readUrlLifetimeStaysBelowOneHour() {
        Long ttlSeconds = (Long) ReflectionTestUtils.getField(
                MediaUrlService.class,
                "READ_SIGNED_URL_TTL_SECONDS"
        );
        Long safetySeconds = (Long) ReflectionTestUtils.getField(
                MediaUrlService.class,
                "READ_SIGNED_URL_CACHE_SAFETY_SECONDS"
        );

        assertEquals(30 * 60L, ttlSeconds);
        assertEquals(5 * 60L, safetySeconds);
        assertTrue(ttlSeconds - safetySeconds > 0);
        assertTrue(ttlSeconds <= 60 * 60L);
    }

    @Test
    void readableUrlListKeepsExternalUrlsUnchanged() {
        MediaUrlService service = new MediaUrlService();
        ReflectionTestUtils.setField(service, "bucketName", "private-media-bucket");

        assertEquals(
                List.of("https://images.example.com/proof.jpg"),
                service.toReadableMediaUrlList("https://images.example.com/proof.jpg")
        );
    }

    @Test
    void privateS3UrlIsNotReturnedUnsignedWhenSigningFails() throws Exception {
        MediaUrlService service = new MediaUrlService();
        ReflectionTestUtils.setField(service, "bucketName", "private-media-bucket");
        Field presignerField = GetSignedUrlForS3.class.getDeclaredField("presigner");
        presignerField.setAccessible(true);
        Object originalPresigner = presignerField.get(null);

        try {
            presignerField.set(null, null);
            String objectKey = "submissions/user_2/SUBMISSION_PHOTO/"
                    + "1783707189331_3ddd0845-29a8-426c-b2d5-98bdae23edfa.jpg";

            assertNull(service.toReadableMediaUrl(objectKey));
            assertTrue(service.toReadableMediaUrlList(objectKey).isEmpty());
        } finally {
            presignerField.set(null, originalPresigner);
        }
    }

    @Test
    void arbitraryBucketObjectIsNeverSignedOrDisclosed() {
        MediaUrlService service = new MediaUrlService();
        ReflectionTestUtils.setField(service, "bucketName", "private-media-bucket");

        assertNull(service.toReadableMediaUrl("exports/all-users.csv"));
        assertNull(service.toReadableMediaUrl(
                "s3://private-media-bucket/private-token-backup.json"));
    }
}
