package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Like;
import com.ironside.challengesphere.model.Media;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.LikeRepository;
import com.ironside.challengesphere.repository.MediaRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LikeService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {
    private final Logger logger = LogManager.getLogger(LikeServiceImpl.class);

    @Autowired
    private final LikeRepository likeRepository;

    @Autowired
    private final MediaRepository mediaRepository;

    @Autowired
    private final UserRepository userRepository;

    @Transactional
    @Override
    public Like likeMedia(Long mediaId, Long userId) throws ChallengeSphereException {
        if (likeRepository.existsByMediaIdAndUserId(mediaId, userId)) {
            throw new ChallengeSphereException(
                "User already liked this media",
                HttpStatus.CONFLICT,
                logger
            );
        }
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new ChallengeSphereException(
                    "Media not found: " + mediaId,
                    HttpStatus.NOT_FOUND,
                    logger
                ));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException(
                    "User not found: " + userId,
                    HttpStatus.NOT_FOUND,
                    logger
                ));
        Like like = Like.builder()
                .media(media)
                .user(user)
                .createdAt(Timestamp.from(Instant.now()))
                .build();
        Like savedLike = likeRepository.save(like);
        media.setLikesCount(media.getLikesCount() + 1);
        mediaRepository.save(media);
        return savedLike;
    }

    @Transactional
    @Override
    public void unlikeMedia(Long mediaId, Long userId) throws ChallengeSphereException {
        try {
            Media media = mediaRepository.findById(mediaId)
                    .orElseThrow(() -> new ChallengeSphereException(
                        "Media not found: " + mediaId,
                        HttpStatus.NOT_FOUND,
                        logger
                    ));
            likeRepository.deleteByMediaIdAndUserId(mediaId, userId);
            media.setLikesCount(Math.max(0, media.getLikesCount() - 1));
            mediaRepository.save(media);
        } catch (Exception e) {
            throw new ChallengeSphereException(
                "Failed to unlike media: " + e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                logger,
                e
            );
        }
    }

    @Override
    public List<Like> getLikesForMedia(Long mediaId) throws ChallengeSphereException {
        try {
            return likeRepository.findByMediaId(mediaId);
        } catch (Exception e) {
            throw new ChallengeSphereException(
                "Failed to fetch likes: " + e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                logger,
                e
            );
        }
    }

    @Override
    public boolean hasUserLiked(Long mediaId, Long userId) throws ChallengeSphereException {
        try {
            return likeRepository.existsByMediaIdAndUserId(mediaId, userId);
        } catch (Exception e) {
            throw new ChallengeSphereException(
                "Failed to check like status: " + e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                logger,
                e
            );
        }
    }

    @Transactional
    @Override
    public boolean toggleLike(Long mediaId, Long userId) throws ChallengeSphereException {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Media not found: " + mediaId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        boolean isLiked = likeRepository.existsByMediaIdAndUserId(mediaId, userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "User not found: " + userId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        if (isLiked) {
            // Unlike
            likeRepository.deleteByMediaIdAndUserId(mediaId, userId);
            media.setLikesCount(Math.max(0, media.getLikesCount() - 1));
            mediaRepository.save(media);
            return false;
        } else {
            // Like
            Like like = new Like();
            like.setMedia(media);
            like.setUser(user); // Ensure Like entity has this setter and field
            like.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            likeRepository.save(like);
            media.setLikesCount(media.getLikesCount() + 1);
            mediaRepository.save(media);
            return true;
        }
    }
}
