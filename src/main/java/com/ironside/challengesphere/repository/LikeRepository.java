package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LikeRepository extends JpaRepository<Like, Long> {
    List<Like> findByMediaId(Long mediaId);
    boolean existsByMediaIdAndUserId(Long mediaId, Long userId);
    void deleteByMediaIdAndUserId(Long mediaId, Long userId);
}

