package com.ironside.challengesphere.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentResponse {
    private Long id;
    private Long userId;
    private String username;
    private String userProfilePic;
    private String text;
    private Long contentId;
    private Long parentCommentId;
    private int likesCount;
    private boolean deleted;
    private boolean isLikedByCurrentUser;
    private Instant createdAt;
    private Instant updatedAt;
    private List<CommentResponse> replies;
}
