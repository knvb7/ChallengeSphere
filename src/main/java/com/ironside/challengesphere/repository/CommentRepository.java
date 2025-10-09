package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query(value = "SELECT * FROM comments WHERE post_id = :postId", nativeQuery = true)
    List<Comment> findByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT * FROM comments WHERE user_id = :userId", nativeQuery = true)
    List<Comment> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM comments WHERE post_id = :postId AND user_id = :userId", nativeQuery = true)
    List<Comment> findByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") Long userId);

    @Query(value = "SELECT * FROM comments WHERE active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Comment> findAllActiveCommentsOrderByCreatedAtDesc();

    @Query(value = "SELECT * FROM comments WHERE post_id = :postId AND active = true ORDER BY created_at ASC", nativeQuery = true)
    List<Comment> findActiveCommentsByPostIdOrderByCreatedAtAsc(@Param("postId") UUID postId);

    @Query(value = "SELECT * FROM comments WHERE user_id = :userId AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Comment> findActiveCommentsByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM comments WHERE content ILIKE %:searchTerm%", nativeQuery = true)
    List<Comment> searchCommentsByContent(@Param("searchTerm") String searchTerm);

    @Query(value = "SELECT COUNT(*) FROM comments WHERE post_id = :postId AND active = true", nativeQuery = true)
    Long countActiveCommentsByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT COUNT(*) FROM comments WHERE user_id = :userId AND active = true", nativeQuery = true)
    Long countActiveCommentsByUserId(@Param("userId") Long userId);

    @Query(value = "UPDATE comments SET active = :active WHERE id = :id", nativeQuery = true)
    void updateCommentActiveStatus(@Param("id") Long id, @Param("active") Boolean active);

    @Query(value = "DELETE FROM comments WHERE post_id = :postId", nativeQuery = true)
    void deleteByPostId(@Param("postId") UUID postId);
}
