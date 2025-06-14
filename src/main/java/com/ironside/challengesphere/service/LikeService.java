package com.ironside.challengesphere.service;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Like;
import java.util.List;

public interface LikeService {
    Like likeMedia(Long mediaId, Long userId) throws ChallengeSphereException;
    void unlikeMedia(Long mediaId, Long userId) throws ChallengeSphereException;
    List<Like> getLikesForMedia(Long mediaId) throws ChallengeSphereException;
    boolean hasUserLiked(Long mediaId, Long userId) throws ChallengeSphereException;
    boolean toggleLike(Long mediaId, Long userId) throws ChallengeSphereException;
}
