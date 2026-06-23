package com.ironside.challengesphere.util;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.config.AwsCredentialsProviderFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Component
public class GetSignedUrlForS3 {

    private static final Logger logger = LogManager.getLogger(GetSignedUrlForS3.class);
    private static final long DEFAULT_EXPIRATION_SECONDS = 3600L;

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.credentials.profile:}")
    private String awsProfile;

    @Value("${aws.credentials.access-keys-csv:}")
    private String accessKeysCsv;

    private static volatile S3Presigner presigner;
    private static volatile S3Client s3Client;

    @PostConstruct
    public void storage() {
        Region region = Region.of(awsRegion);
        AwsCredentialsProvider credentialsProvider = AwsCredentialsProviderFactory.create(awsProfile, accessKeysCsv);
        presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();
        s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();
        logger.info("S3 clients initialized for region {}", awsRegion);
    }

    @PreDestroy
    public void close() {
        S3Presigner localPresigner = presigner;
        S3Client localS3Client = s3Client;
        if (localPresigner != null) {
            localPresigner.close();
        }
        if (localS3Client != null) {
            localS3Client.close();
        }
    }

    public static String generatePutObjectSignedUrl(
            String bucketName,
            String objectName,
            Long expirationTime,
            GeneratePresignedUrlsRequest.ContentType contentType
    ) {
        PutObjectRequest.Builder objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName);
        if (contentType != null) {
            objectRequest.contentType(contentType.getMimeType());
        }

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(resolveExpiration(expirationTime)))
                .putObjectRequest(objectRequest.build())
                .build();

        String signedUrl = presigner().presignPutObject(presignRequest).url().toString();
        logger.info("Generated S3 PUT signed URL for object: {}", objectName);
        return signedUrl;
    }

    public static String generateV4GetObjectSignedUrl(
            String bucketName,
            String objectName,
            Long expirationTime
    ) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(resolveExpiration(expirationTime)))
                .getObjectRequest(objectRequest)
                .build();

        String signedUrl = presigner().presignGetObject(presignRequest).url().toString();
        logger.debug("Generated S3 GET signed URL for object: {}", objectName);
        return signedUrl;
    }

    public static void updateObjectCacheControl(String bucketName, String objectName, String cacheControl) {
        HeadObjectResponse headObject;
        try {
            headObject = s3Client().headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                logger.warn("Skipping cache-control update; S3 object not found: {}", objectName);
                return;
            }
            throw e;
        }

        CopyObjectRequest.Builder copyRequest = CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(objectName)
                .destinationBucket(bucketName)
                .destinationKey(objectName)
                .metadataDirective(MetadataDirective.REPLACE)
                .cacheControl(cacheControl);
        if (headObject.contentType() != null) {
            copyRequest.contentType(headObject.contentType());
        }
        if (headObject.metadata() != null && !headObject.metadata().isEmpty()) {
            copyRequest.metadata(headObject.metadata());
        }

        s3Client().copyObject(copyRequest.build());
        logger.debug("Updated cache-control for S3 object {} to {}", objectName, cacheControl);
    }

    public static void deleteObject(String bucketName, String objectName) {
        try {
            s3Client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build());
            logger.info("Deleted S3 object: {}", objectName);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                logger.warn("S3 object was not found for deletion: {}", objectName);
                return;
            }
            throw e;
        }
    }

    public static void uploadObject(String bucketName, String objectName, byte[] content, String contentType) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentType(contentType)
                .cacheControl("private, max-age=3600")
                .build();
        s3Client().putObject(objectRequest, RequestBody.fromBytes(content));
        logger.info("Uploaded object to S3: {}", objectName);
    }

    private static long resolveExpiration(Long expirationTime) {
        return expirationTime == null || expirationTime <= 0 ? DEFAULT_EXPIRATION_SECONDS : expirationTime;
    }

    private static S3Presigner presigner() {
        if (presigner == null) {
            throw new IllegalStateException("S3 presigner has not been initialized");
        }
        return presigner;
    }

    private static S3Client s3Client() {
        if (s3Client == null) {
            throw new IllegalStateException("S3 client has not been initialized");
        }
        return s3Client;
    }

}
