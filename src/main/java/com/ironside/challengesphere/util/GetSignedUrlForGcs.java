package com.ironside.challengesphere.util;

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
        storage = StorageOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(ServiceAccountCredentials.fromStream(new FileInputStream(keyPath)))
                .build()
                .getService();
    }


    public static String generateV4PutObjectSignedUrl(
            String bucketName,
            String objectName,
            Long expirationTime,
            GeneratePresignedUrlsRequest.ContentType contentType
    ) {
        // --- Step 2: Define object metadata ---
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName))
                .setContentType(String.valueOf(contentType))
                .build();

        // --- Step 3: Generate signed URL (V4, PUT method) ---
        URL signedUrl = storage.signUrl(
                blobInfo,
                expirationTime,
                TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature() // REQUIRED for modern GCS
        );

        logger.info("✅ Generated PUT signed URL: {}", signedUrl);
        return signedUrl.toString();
    }
}