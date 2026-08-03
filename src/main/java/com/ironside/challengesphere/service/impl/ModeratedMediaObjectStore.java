package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.config.AwsCredentialsProviderFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@Component
public class ModeratedMediaObjectStore {

    private final S3Client s3Client;

    public ModeratedMediaObjectStore(
            @Value("${aws.region}") String awsRegion,
            @Value("${aws.credentials.profile:}") String awsProfile) {
        this.s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(AwsCredentialsProviderFactory.create(awsProfile))
                .build();
    }

    void delete(String bucketName, String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build());
    }

    @PreDestroy
    void close() {
        s3Client.close();
    }
}
