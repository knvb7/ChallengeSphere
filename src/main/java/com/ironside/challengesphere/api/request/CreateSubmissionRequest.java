package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateSubmissionRequest {
    private String userId;

    @NotBlank(message = "Challenge ID is required")
    private String challengeId;

    @NotNull(message = "Day number is required")
    @Min(value = 1, message = "Day number must be at least 1")
    private Integer dayNumber;

    @NotBlank(message = "Description is required")
    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String description;

    @NotEmpty(message = "At least one submission photo is required")
    @Size(min = 1, max = 5, message = "A submission must include between 1 and 5 photos")
    private List<String> mediaUrls; // Owned S3 photo keys issued by the upload flow

    private Boolean isPrivate = false; // Default to public submission

    @Size(max = 64, message = "Timezone cannot exceed 64 characters")
    private String timezone; // IANA timezone reported by the submitting device
}
