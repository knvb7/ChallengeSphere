package com.ironside.challengesphere.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

final class AwsRdsIamAuthTokenProvider implements RdsIamAuthTokenProvider {

    private static final Logger logger = LogManager.getLogger(AwsRdsIamAuthTokenProvider.class);
    private final String hostname;
    private final int port;
    private final RdsUtilities rdsUtilities;

    AwsRdsIamAuthTokenProvider(String region, String hostname, int port, String profileName, String accessKeysCsvPath) {
        this(region, hostname, port, AwsCredentialsProviderFactory.create(profileName, accessKeysCsvPath));
    }

    AwsRdsIamAuthTokenProvider(String region, String hostname, int port, AwsCredentialsProvider credentialsProvider) {
        this.hostname = hostname;
        this.port = port;
        this.rdsUtilities = RdsUtilities.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    @Override
    public String generateToken(String username) {
        String token = rdsUtilities.generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(hostname)
                .port(port)
                .username(username)
                .build());
        logger.debug("Generated RDS IAM auth token for user '{}' at {}:{}", username, hostname, port);
        return token;
    }

}
