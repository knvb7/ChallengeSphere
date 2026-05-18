package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowRelationshipResponse {

    private Long viewerUserId;
    private Long targetUserId;
    private Boolean isFollowing;
    private Boolean isFollowedBy;

    public static FollowRelationshipResponse of(Long viewerUserId,
                                                Long targetUserId,
                                                Boolean isFollowing,
                                                Boolean isFollowedBy) {
        return new FollowRelationshipResponse(viewerUserId, targetUserId, isFollowing, isFollowedBy);
    }
}
