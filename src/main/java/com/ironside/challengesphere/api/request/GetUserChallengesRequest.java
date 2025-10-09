package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GetUserChallengesRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @Min(value = 1, message = "Page must be greater than 0")
    private Integer page = 1;

    @Min(value = 1, message = "Page size must be greater than 0")
    private Integer pageSize = 10;

    // Optional filter for challenge status
    private Boolean active;
}
