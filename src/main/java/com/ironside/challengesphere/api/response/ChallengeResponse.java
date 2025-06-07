package com.ironside.challengesphere.api.response;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class ChallengeResponse {
    private Long id;
    private String title;
    private String description;
    private Long creatorUserId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private boolean active;
}

