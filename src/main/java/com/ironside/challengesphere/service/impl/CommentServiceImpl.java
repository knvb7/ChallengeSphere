//package com.ironside.challengesphere.service.impl;
//
//import com.ironside.challengesphere.exception.ChallengeSphereException;
//import com.ironside.challengesphere.service.CommentService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.Pageable;
//import org.springframework.http.HttpStatus;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.sql.Timestamp;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class CommentServiceImpl implements CommentService {
//    private final Logger logger = LogManager.getLogger(CommentServiceImpl.class);
//
//    @Autowired
//    private final CommentRepository commentRepository;
//
//    @Autowired
//    private final MediaRepository mediaRepository;
//
//    @Autowired
//    private final UserRepository userRepository;
//
//    @Transactional
//    @Override
//    public Comment addComment(Long mediaId, Long userId, String content) throws ChallengeSphereException {
//        Media media = mediaRepository.findById(mediaId)
//                .orElseThrow(() -> new ChallengeSphereException(
//                    "Media not found: " + mediaId,
//                    HttpStatus.NOT_FOUND,
//                    logger
//                ));
//        User user = userRepository.findById(userId)
//                .orElseThrow(() -> new ChallengeSphereException(
//                    "User not found: " + userId,
//                    HttpStatus.NOT_FOUND,
//                    logger
//                ));
//        Comment comment = new Comment();
//        comment.setMedia(media);
//        comment.setUser(user);
//        comment.setContent(content);
//        comment.setCreatedAt(new Timestamp(System.currentTimeMillis()));
//        Comment savedComment = commentRepository.save(comment);
//        media.setCommentsCount(media.getCommentsCount() + 1);
//        mediaRepository.save(media);
//        return savedComment;
//    }
//
//    @Override
//    public Page<Comment> getCommentsForMedia(Long mediaId, Pageable pageable) {
//        return commentRepository.findByMediaId(mediaId, pageable);
//    }
//
//    @Transactional
//    @Override
//    public void softDeleteComment(Long commentId, Long userId) throws ChallengeSphereException {
//        try {
//            Comment comment = commentRepository.findById(commentId)
//                    .orElseThrow(() -> new ChallengeSphereException(
//                        "Comment not found: " + commentId,
//                        HttpStatus.NOT_FOUND,
//                        logger
//                    ));
//            comment.setActive(false);
//            commentRepository.save(comment);
//            Media media = comment.getMedia();
//            media.setCommentsCount(Math.max(0, media.getCommentsCount() - 1));
//            mediaRepository.save(media);
//        } catch (Exception e) {
//            logger.error("Error soft deleting comment with id={} by userId={}: {}", commentId, userId, e.getMessage());
//            throw new ChallengeSphereException(
//                "Failed to soft delete comment: " + e.getMessage(),
//                HttpStatus.INTERNAL_SERVER_ERROR,
//                logger
//            );
//        }
//    }
//}
