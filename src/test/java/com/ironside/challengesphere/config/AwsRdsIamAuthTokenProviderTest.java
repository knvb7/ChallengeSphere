package com.ironside.challengesphere.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

class AwsRdsIamAuthTokenProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesTokenUsingProvidedCredentialsProvider() {
        AtomicInteger credentialResolutions = new AtomicInteger();
        AwsCredentialsProvider credentialsProvider = () -> {
            credentialResolutions.incrementAndGet();
            return AwsBasicCredentials.create("access-key", "secret-key");
        };

        AwsRdsIamAuthTokenProvider tokenProvider = new AwsRdsIamAuthTokenProvider(
                "ap-south-1",
                "database-1.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com",
                5432,
                credentialsProvider
        );

        String token = tokenProvider.generateToken("postgres");

        assertThat(credentialResolutions).hasValue(1);
        assertThat(token)
                .contains("database-1.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com:5432")
                .contains("Action=connect")
                .contains("DBUser=postgres")
                .contains("X-Amz-Credential=access-key");
    }

    @Test
    void generatesTokenUsingAccessKeysCsv() throws Exception {
        Path accessKeysCsv = tempDir.resolve("challengesphere_accessKeys.csv");
        Files.writeString(accessKeysCsv, """
                Access key ID,Secret access key
                csv-access-key,csv-secret-key
                """);

        AwsRdsIamAuthTokenProvider tokenProvider = new AwsRdsIamAuthTokenProvider(
                "ap-south-1",
                "database-1.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com",
                5432,
                "",
                accessKeysCsv.toString()
        );

        String token = tokenProvider.generateToken("postgres");

        assertThat(token)
                .contains("DBUser=postgres")
                .contains("X-Amz-Credential=csv-access-key");
    }
}
