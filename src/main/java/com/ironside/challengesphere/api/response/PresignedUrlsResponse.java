package com.ironside.challengesphere.api.response;

import lombok.Data;

import java.util.List;

@Data
public class PresignedUrlsResponse {
    private boolean success;
    private String message;
    private Long userId;
    private List<PresignedUrlInfo> presignedUrls;

    @Data
    public static class PresignedUrlInfo {
        private String fileName;
        private String fileType;
        private String contentType;
        private String presignedUrl;
        private String fileKey; // The S3 key/path where file will be stored
        private Long expiresIn; // Expiration time in seconds

        public PresignedUrlInfo(String fileName, String fileType, String contentType,
                                String presignedUrl, String fileKey, Long expiresIn) {
            this.fileName = fileName;
            this.fileType = fileType;
            this.contentType = contentType;
            this.presignedUrl = presignedUrl;
            this.fileKey = fileKey;
            this.expiresIn = expiresIn;
        }
    }

    public static PresignedUrlsResponse success(Long userId, List<PresignedUrlInfo> presignedUrls) {
        PresignedUrlsResponse response = new PresignedUrlsResponse();
        response.setSuccess(true);
        response.setMessage("Presigned URLs generated successfully");
        response.setUserId(userId);
        response.setPresignedUrls(presignedUrls);
        return response;
    }

    public static PresignedUrlsResponse failure(String message) {
        PresignedUrlsResponse response = new PresignedUrlsResponse();
        response.setSuccess(false);
        response.setMessage(message);
        return response;
    }
}
