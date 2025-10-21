package com.ironside.challengesphere.util;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GetSignedUrlForGcs {
    /**
     * Signing a URL requires Credentials which implement ServiceAccountSigner. These can be set
     * explicitly using the Storage.SignUrlOption.signWith(ServiceAccountSigner) option. If you don't,
     * you could also pass a service account signer to StorageOptions, i.e.
     * StorageOptions().newBuilder().setCredentials(ServiceAccountSignerCredentials). In this example,
     * neither of these options are used, which means the following code only works when the
     * credentials are defined via the environment variable GOOGLE_APPLICATION_CREDENTIALS, and those
     * credentials are authorized to sign a URL. See the documentation for Storage.signUrl for more
     * details.
     */
    public static String generateV4PutObjectSignedUrl(String projectId, String bucketName, String objectName, Integer expirationTime, GeneratePresignedUrlsRequest.ContentType contentType) throws Exception {
        // String projectId = "my-project-id";
        // String bucketName = "my-bucket";
        // String objectName = "my-object";

        Storage storage = StorageOptions.newBuilder().setProjectId(projectId).build().getService();

        // Define Resource
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName)).setContentType(contentType.getMimeType()).build();

        // Generate Signed URL
        Map<String, String> extensionHeaders = new HashMap<>();
        extensionHeaders.put("Content-Type", "application/octet-stream");

        URL url = storage.signUrl(
                blobInfo,
                expirationTime,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withExtHeaders(extensionHeaders),
                Storage.SignUrlOption.withV4Signature());

        System.out.println("Generated PUT signed URL:");
        System.out.println(url);
        System.out.println("You can use this URL with any user agent, for example:");
        System.out.println(
                "curl -X PUT -H 'Content-Type: application/octet-stream' --upload-file my-file '"
                        + url
                        + "'");
        return url.toString();
    }
}