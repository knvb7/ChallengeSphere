package com.ironside.challengesphere.api.request;

import com.ironside.challengesphere.util.MediaTypes;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
public class GeneratePresignedUrlsRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotEmpty(message = "At least one media file is required")
    private List<MediaFileInfo> mediaFiles;

    @Data
    public static class MediaFileInfo {
        @NotNull(message = "File name is required")
        private String fileName;

        @NotNull(message = "File type is required")
        private MediaTypes mediaType; // e.g., "image", "video"

        @NotNull(message = "Content type is required")
        private ContentType contentType; // Using existing ContentType enum
    }

    @Getter
    public enum ContentType {
        IMAGE_JPEG("image/jpeg"),
        IMAGE_PNG("image/png"),
        IMAGE_GIF("image/gif"),
        VIDEO_MP4("video/mp4");
        private final String mimeType;

        ContentType(String mimeType) {
            this.mimeType = mimeType;
        }

    }
}
