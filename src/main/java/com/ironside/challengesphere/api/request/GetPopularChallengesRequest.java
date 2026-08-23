package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.LocalDate;

@Data
public class GetPopularChallengesRequest {
    private String currentUserId;

    private Boolean active;

    private LocalDate startDate;

    private LocalDate endDate;

    private String cursor;

    @Min(value = 1, message = "Limit must be at least 1")
    private int limit = 10;
}
