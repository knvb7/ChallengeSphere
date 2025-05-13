package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SubmissionRequest {
    @NotBlank(message = "Media URL is required")
    private String mediaUrl;
    
    private String authenticityCode;
    
    @Size(max = 1000, message = "Notes cannot exceed 1000 characters")
    private String notes;
    
    // Optional, defaults to current date if not provided
    private LocalDate submissionDate;
}
