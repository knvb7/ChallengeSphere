package com.ironside.challengesphere.api.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GetUserChallengesRequest {

    @NotBlank(message = "User ID is required")
    private String userId;

    private String currentUserId;

    private String cursor;

    @Min(value = 1, message = "Limit must be greater than 0")
    private Integer limit = 10;

    // Optional filter for challenge status
    private Boolean active;
}
