package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ContentReportRequest {

    @NotBlank
    @Size(max = 32)
    private String targetType;

    @NotBlank
    private String targetId;

    @NotBlank
    @Size(max = 64)
    private String reason;

    @Size(max = 2000)
    private String details;
}
