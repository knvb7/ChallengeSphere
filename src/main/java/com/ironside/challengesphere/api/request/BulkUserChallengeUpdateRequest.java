package com.ironside.challengesphere.api.request;

import lombok.Data;

import java.util.List;

@Data
public class BulkUserChallengeUpdateRequest {
    private List<Long> userIds;
}

