package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SubmissionPreviewsRequest {

    @NotEmpty
    @Size(max = 50)
    private List<@NotBlank String> challengeIds;

    private String userId;
}
