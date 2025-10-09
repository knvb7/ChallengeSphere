//package com.ironside.challengesphere.service;
//
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.Pageable;
//
//public interface CommentService {
//    Comment addComment(Long mediaId, Long userId, String content) throws ChallengeSphereException;
//    Page<Comment> getCommentsForMedia(Long mediaId, Pageable pageable);
//    void softDeleteComment(Long commentId, Long userId) throws ChallengeSphereException;
//}
