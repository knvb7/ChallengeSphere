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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
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

    private static volatile S3Presigner presigner;
    private static volatile S3Client s3Client;

    @PostConstruct
    public void storage() {
        AwsCredentialsProvider credentialsProvider = AwsCredentialsProviderFactory.create(awsProfile);
        storage(credentialsProvider);
    }

    void storage(AwsCredentialsProvider credentialsProvider) {
        Region region = Region.of(awsRegion);
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
            GeneratePresignedUrlsRequest.ContentType contentType,
            Long contentLength
    ) {
        if (contentLength == null || contentLength <= 0 || contentLength > 8L * 1024 * 1024) {
            throw new IllegalArgumentException("Upload content length must be between 1 byte and 8 MB");
        }
        PutObjectRequest.Builder objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentLength(contentLength)
                .cacheControl("private, max-age=3600")
                .ifNoneMatch("*");
        if (contentType != null) {
            objectRequest.contentType(contentType.getMimeType());
        }

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(resolveExpiration(expirationTime)))
                .putObjectRequest(objectRequest.build())
                .build();

        String signedUrl = presigner().presignPutObject(presignRequest).url().toString();
        logger.debug("Generated S3 PUT signed URL");
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
        logger.debug("Generated S3 GET signed URL");
        return signedUrl;
    }

    public static boolean objectExists(String bucketName, String objectName) {
        try {
            s3Client().headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
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
        logger.debug("Uploaded object to S3");
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
