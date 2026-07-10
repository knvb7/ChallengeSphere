package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class CreateSubmissionRequest {
    private Long userId;

    @NotNull(message = "Challenge ID is required")
    private Long challengeId;

    @NotNull(message = "Day number is required")
    @Min(value = 1, message = "Day number must be at least 1")
    private Integer dayNumber;

    @NotBlank(message = "Description is required")
    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String description;

    @Size(max = 5, message = "A submission can include at most 5 media files")
    private List<String> mediaUrls; // Image/video URLs

    private Boolean isPrivate = false; // Default to public submission

    private Timestamp submissionDate; // Defaults to today if not provided
}
