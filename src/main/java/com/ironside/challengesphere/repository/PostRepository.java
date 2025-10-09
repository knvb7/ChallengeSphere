package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {

    @Query(value = "SELECT * FROM posts WHERE user_id = :userId", nativeQuery = true)
    List<Post> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM posts WHERE challenge_id = :challengeId", nativeQuery = true)
    List<Post> findByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM posts WHERE user_id = :userId AND challenge_id = :challengeId", nativeQuery = true)
    List<Post> findByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM posts WHERE active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Post> findAllActivePostsOrderByCreatedAtDesc();

    @Query(value = "SELECT * FROM posts WHERE active = true AND user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Post> findActivePostsByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM posts WHERE active = true AND challenge_id = :challengeId ORDER BY created_at DESC", nativeQuery = true)
    List<Post> findActivePostsByChallengeIdOrderByCreatedAtDesc(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM posts WHERE title ILIKE %:searchTerm% OR content ILIKE %:searchTerm%", nativeQuery = true)
    List<Post> searchPostsByTitleOrContent(@Param("searchTerm") String searchTerm);

    @Query(value = "UPDATE posts SET likes_count = likes_count + :increment WHERE id = :id", nativeQuery = true)
    void updateLikesCount(@Param("id") UUID id, @Param("increment") Long increment);

    @Query(value = "UPDATE posts SET comments_count = comments_count + :increment WHERE id = :id", nativeQuery = true)
    void updateCommentsCount(@Param("id") UUID id, @Param("increment") Long increment);

    @Query(value = "UPDATE posts SET active = :active WHERE id = :id", nativeQuery = true)
    void updatePostActiveStatus(@Param("id") UUID id, @Param("active") Boolean active);

    @Query(value = "SELECT COUNT(*) FROM posts WHERE user_id = :userId AND active = true", nativeQuery = true)
    Long countActivePostsByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM posts WHERE challenge_id = :challengeId AND active = true", nativeQuery = true)
    Long countActivePostsByChallengeId(@Param("challengeId") Long challengeId);
}
