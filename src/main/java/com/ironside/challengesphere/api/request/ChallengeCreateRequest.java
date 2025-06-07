package com.ironside.challengesphere.api.request;

import lombok.Data;

@Data
public class ChallengeCreateRequest {
    private String title;
    private String description;
    private Long creatorUserId;
}

