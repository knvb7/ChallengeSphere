package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class GetChallengesByCategoryRequest {
    private List<String> categories;

    private Boolean active;

    @Min(value = 1, message = "Page must be at least 1")
    private int page = 1;

    @Min(value = 1, message = "Page size must be at least 1")
    private int pageSize = 10;
}

