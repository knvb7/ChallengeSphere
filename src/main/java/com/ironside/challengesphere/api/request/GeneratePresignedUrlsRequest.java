package com.ironside.challengesphere.api.request;

import com.ironside.challengesphere.util.MediaTypes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
public class GeneratePresignedUrlsRequest {

    private String userId;

    @NotEmpty(message = "At least one media file is required")
    @Size(max = 5, message = "A request can include at most 5 media files")
    @Valid
    private List<MediaFileInfo> mediaFiles;

    @Data
    public static class MediaFileInfo {
        @NotBlank(message = "File name is required")
        private String fileName;

        @NotNull(message = "File type is required")
        private MediaTypes mediaType; // e.g., "image", "video"

        @NotNull(message = "Content type is required")
        private ContentType contentType; // Using existing ContentType enum

        @NotNull(message = "File size is required")
        @Positive(message = "File size must be positive")
        @Max(value = 8388608, message = "File size must not exceed 8 MB")
        private Long fileSizeBytes;
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
