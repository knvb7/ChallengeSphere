package com.ironside.challengesphere.util;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.*;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.File;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Component
public class GetSignedUrlForGcs {

    private static final Logger logger = LogManager.getLogger(GetSignedUrlForGcs.class);

    @Value("${gcs.key.path}")
    private String keyPath;

    @Value("${gcs.projectId}")
    private String projectId;

    private static Storage storage;

    @Scheduled(initialDelay = 0, fixedDelay = Long.MAX_VALUE)
    public void storage() throws IOException {
        GoogleCredentials credentials = resolveCredentials();
        storage = StorageOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(credentials)
                .build()
                .getService();
        logger.info("GCS client initialized for project {}", projectId);
    }

    private GoogleCredentials resolveCredentials() throws IOException {
        if (keyPath != null && !keyPath.isBlank() && new File(keyPath).exists()) {
            return ServiceAccountCredentials.fromStream(new FileInputStream(keyPath));
        }
        return GoogleCredentials.getApplicationDefault();
    }


    public static String generatePutObjectSignedUrl(
            String bucketName,
            String objectName,
            Long expirationTime,
            GeneratePresignedUrlsRequest.ContentType contentType
    ) {
        // Define object metadata with the correct MIME content type
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName))
                .setContentType(contentType.getMimeType())
                .build();

        // V4 with only the host header signed avoids V2's Content-Type-sensitive string-to-sign.
        URL signedUrl = storage.signUrl(
                blobInfo,
                expirationTime,
                TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature()
        );

        logger.info("Generated V4 PUT signed URL for object: {}", objectName);
        return signedUrl.toString();
    }

    public static String generateV4GetObjectSignedUrl(
            String bucketName,
            String objectName,
            Long expirationTime
    ) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName)).build();

        URL signedUrl = storage.signUrl(
                blobInfo,
                expirationTime,
                TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature()
        );

        logger.debug("Generated V4 GET signed URL for object: {}", objectName);
        return signedUrl.toString();
    }

    public static void updateObjectCacheControl(String bucketName, String objectName, String cacheControl) {
        Blob blob = storage.get(BlobId.of(bucketName, objectName));
        if (blob == null) {
            logger.warn("Skipping cache-control update; GCS object not found: {}", objectName);
            return;
        }

        blob.toBuilder()
                .setCacheControl(cacheControl)
                .build()
                .update();
        logger.debug("Updated cache-control for GCS object {} to {}", objectName, cacheControl);
    }

    public static boolean deleteObject(String bucketName, String objectName) {
        boolean deleted = storage.delete(BlobId.of(bucketName, objectName));
        if (deleted) {
            logger.info("Deleted GCS object: {}", objectName);
        } else {
            logger.warn("GCS object was not found for deletion: {}", objectName);
        }
        return deleted;
    }

    public static void uploadObject(String bucketName, String objectName, byte[] content, String contentType) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName))
                .setContentType(contentType)
                .setCacheControl("private, max-age=3600")
                .build();
        storage.create(blobInfo, content);
        logger.info("Uploaded object to GCS: {}", objectName);
    }
}
