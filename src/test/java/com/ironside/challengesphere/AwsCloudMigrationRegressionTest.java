package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsCloudMigrationRegressionTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void backendStorageModerationAndDatabaseInfraUseAwsInsteadOfGoogleCloud() throws Exception {
        String pom = read("pom.xml");
        assertFalse(pom.contains("google-cloud-storage"));
        assertFalse(pom.contains("google-cloud-vision"));
        assertFalse(pom.contains("postgres-socket-factory"));
        assertTrue(pom.contains("<artifactId>s3</artifactId>"));
        assertTrue(pom.contains("<artifactId>rekognition</artifactId>"));

        assertFalse(Files.exists(Path.of("src/main/java/com/ironside/challengesphere/util/GetSignedUrlForGcs.java")));
        String s3Signer = read("src/main/java/com/ironside/challengesphere/util/GetSignedUrlForS3.java");
        assertTrue(s3Signer.contains("S3Presigner"));
        assertTrue(s3Signer.contains("S3Client"));
        assertFalse(s3Signer.contains("com.google.cloud"));
        assertFalse(s3Signer.contains("${gcs."));

        String mediaUrlService = read("src/main/java/com/ironside/challengesphere/service/impl/MediaUrlService.java");
        assertTrue(mediaUrlService.contains("GetSignedUrlForS3"));
        assertTrue(mediaUrlService.contains("extractS3ObjectName"));
        assertFalse(mediaUrlService.contains("GetSignedUrlForGcs"));
        assertFalse(mediaUrlService.contains("storage.googleapis.com"));
        assertFalse(mediaUrlService.contains("GCS"));

        String submissionService = read("src/main/java/com/ironside/challengesphere/service/impl/SubmissionServiceImpl.java");
        assertTrue(submissionService.contains("@Value(\"${aws.s3.bucket.name}\")"));
        assertTrue(submissionService.contains("GetSignedUrlForS3.generatePutObjectSignedUrl"));
        assertFalse(submissionService.contains("${gcs."));
        assertFalse(submissionService.contains("Google Cloud Storage"));
        assertFalse(submissionService.contains("GetSignedUrlForGcs"));

        String challengeService = read("src/main/java/com/ironside/challengesphere/service/impl/ChallengeServiceImpl.java");
        assertTrue(challengeService.contains("GetSignedUrlForS3.generatePutObjectSignedUrl"));
        assertFalse(challengeService.contains("GetSignedUrlForGcs"));
        assertFalse(challengeService.contains("storage.googleapis.com"));
        assertFalse(challengeService.contains("gs://"));

        String moderationService = read("src/main/java/com/ironside/challengesphere/service/impl/ContentModerationServiceImpl.java");
        assertTrue(moderationService.contains("RekognitionClient"));
        assertTrue(moderationService.contains("DetectModerationLabelsRequest"));
        assertFalse(moderationService.contains("ImageAnnotatorClient"));
        assertFalse(moderationService.contains("GoogleCredentials"));
        assertFalse(moderationService.contains("${gcs."));
        assertFalse(moderationService.contains("Vision"));
    }
}
