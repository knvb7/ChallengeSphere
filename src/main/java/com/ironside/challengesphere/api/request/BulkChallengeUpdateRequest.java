package com.ironside.challengesphere.api.request;

import lombok.Data;

import java.util.List;

@Data
public class BulkChallengeUpdateRequest {
    private List<String> challengeIds;
    private Boolean disable;
    private Boolean softDelete;
}
