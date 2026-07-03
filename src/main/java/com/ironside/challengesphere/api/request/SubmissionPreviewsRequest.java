package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SubmissionPreviewsRequest {

    @NotEmpty
    @Size(max = 50)
    private List<@NotNull @Positive Long> challengeIds;

    @Positive
    private Long userId;
}
