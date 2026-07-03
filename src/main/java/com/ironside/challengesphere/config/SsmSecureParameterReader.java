package com.ironside.challengesphere.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@Component
public class SsmSecureParameterReader {

    private final String awsRegion;
    private final String awsProfile;

    public SsmSecureParameterReader(
            @Value("${aws.region}") String awsRegion,
            @Value("${aws.credentials.profile:}") String awsProfile
    ) {
        this.awsRegion = awsRegion;
        this.awsProfile = awsProfile;
    }

    public String read(String parameterName) {
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalStateException("Secure parameter name is not configured");
        }
        try (SsmClient client = SsmClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(AwsCredentialsProviderFactory.create(awsProfile))
                .build()) {
            return client.getParameter(GetParameterRequest.builder()
                            .name(parameterName)
                            .withDecryption(true)
                            .build())
                    .parameter()
                    .value();
        }
    }
}
