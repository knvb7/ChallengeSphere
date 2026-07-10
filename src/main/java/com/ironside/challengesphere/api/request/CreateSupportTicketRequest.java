package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CreateSupportTicketRequest {

    @NotNull(message = "Issue type is required")
    private IssueType issueType;

    @NotBlank(message = "Subject is required")
    @Size(min = 3, max = 120, message = "Subject must be between 3 and 120 characters")
    private String subject;

    @NotBlank(message = "Message is required")
    @Size(min = 10, max = 4000, message = "Message must be between 10 and 4000 characters")
    private String message;

    @Size(max = 80, message = "App version should not exceed 80 characters")
    private String appVersion;

    @Size(max = 80, message = "Platform should not exceed 80 characters")
    private String platform;

    @Size(max = 200, message = "Device info should not exceed 200 characters")
    private String deviceInfo;

    @Size(max = 3, message = "A support request can include at most 3 screenshots")
    private List<@Size(max = 160, message = "Screenshot reference is too long") String> screenshotKeys = new ArrayList<>();

    public enum IssueType {
        QUESTION("Question"),
        BUG("Bug"),
        ACCOUNT("Account"),
        CHALLENGE("Challenge"),
        MEDIA_UPLOAD("Media upload"),
        OTHER("Other");

        private final String label;

        IssueType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
