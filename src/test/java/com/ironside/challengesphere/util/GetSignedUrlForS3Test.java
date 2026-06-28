package com.ironside.challengesphere.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GetSignedUrlForS3Test {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        new GetSignedUrlForS3().close();
    }

    @Test
    void generatesPutUrlUsingAccessKeysCsvCredentials() throws Exception {
        Path accessKeysCsv = tempDir.resolve("challengesphere_accessKeys.csv");
        Files.writeString(accessKeysCsv, """
                Access key ID,Secret access key
                csv-access-key,csv-secret-key
                """);

        GetSignedUrlForS3 signer = new GetSignedUrlForS3();
        setField(signer, "awsRegion", "ap-south-1");
        setField(signer, "accessKeysCsv", accessKeysCsv.toString());
        signer.storage();

        String signedUrl = GetSignedUrlForS3.generatePutObjectSignedUrl(
                "challengesphere-submissions-prod-ap-south-1",
                "submissions/test-image.jpg",
                3600L,
                GeneratePresignedUrlsRequest.ContentType.IMAGE_JPEG
        );

        assertThat(signedUrl)
                .contains("challengesphere-submissions-prod-ap-south-1.s3.ap-south-1.amazonaws.com")
                .contains("submissions/test-image.jpg")
                .contains("X-Amz-Credential=csv-access-key");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
