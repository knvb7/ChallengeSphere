package com.ironside.challengesphere.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaUrlServiceTest {

    @Test
    void toGcsObjectNamesParsesBareGsAndStorageUrls() {
        MediaUrlService service = new MediaUrlService();
        ReflectionTestUtils.setField(service, "bucketName", "challengesphere-media");

        List<String> objectNames = service.toGcsObjectNames(List.of(
                "submissions/user_3/SUBMISSION_PHOTO/bare.jpg",
                "gs://challengesphere-media/submissions/user_3/SUBMISSION_PHOTO/gs.jpg",
                "https://storage.googleapis.com/challengesphere-media/submissions/user_3/SUBMISSION_PHOTO/url.jpg?X-Goog-Signature=abc"
        ));

        assertEquals(List.of(
                "submissions/user_3/SUBMISSION_PHOTO/bare.jpg",
                "submissions/user_3/SUBMISSION_PHOTO/gs.jpg",
                "submissions/user_3/SUBMISSION_PHOTO/url.jpg"
        ), objectNames);
    }

    @Test
    void toStoredMediaUrlsCompactsGcsUrlsAndPreservesExternalUrls() {
        MediaUrlService service = new MediaUrlService();
        ReflectionTestUtils.setField(service, "bucketName", "challengesphere-media");

        List<String> storedUrls = service.toStoredMediaUrls(List.of(
                "https://storage.googleapis.com/challengesphere-media/submissions/user_3/IMAGE/photo.jpg?X-Goog-Signature=abc",
                "gs://challengesphere-media/submissions/user_3/IMAGE/gs-photo.jpg",
                "https://cdn.example.com/external-photo.jpg?cache=1"
        ));

        assertEquals(List.of(
                "submissions/user_3/IMAGE/photo.jpg",
                "submissions/user_3/IMAGE/gs-photo.jpg",
                "https://cdn.example.com/external-photo.jpg"
        ), storedUrls);
    }
}
