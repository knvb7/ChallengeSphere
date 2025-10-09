package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LikeRepository extends JpaRepository<Like, Long> {

    @Query(value = "SELECT * FROM likes WHERE post_id = :postId", nativeQuery = true)
    List<Like> findByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT * FROM likes WHERE user_id = :userId", nativeQuery = true)
    List<Like> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM likes WHERE post_id = :postId AND user_id = :userId", nativeQuery = true)
    Optional<Like> findByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") Long userId);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM likes WHERE post_id = :postId AND user_id = :userId", nativeQuery = true)
    boolean existsByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") Long userId);

    @Query(value = "DELETE FROM likes WHERE post_id = :postId AND user_id = :userId", nativeQuery = true)
    void deleteByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM likes WHERE post_id = :postId AND active = true", nativeQuery = true)
    Long countActiveLikesByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT COUNT(*) FROM likes WHERE user_id = :userId AND active = true", nativeQuery = true)
    Long countActiveLikesByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM likes WHERE active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Like> findAllActiveLikesOrderByCreatedAtDesc();

    @Query(value = "UPDATE likes SET active = :active WHERE id = :id", nativeQuery = true)
    void updateLikeActiveStatus(@Param("id") Long id, @Param("active") Boolean active);
}
