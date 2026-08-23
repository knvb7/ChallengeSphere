package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class GetChallengesByCategoryRequest {
    private List<String> categories;

    private Boolean active;

    private String cursor;

    @Min(value = 1, message = "Limit must be at least 1")
    private int limit = 10;
}
