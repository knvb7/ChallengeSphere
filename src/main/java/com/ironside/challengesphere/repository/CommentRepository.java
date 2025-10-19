package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    // Queries for submissions
    @Query(value = "SELECT * FROM challengesphere.comments WHERE submission_id = :submissionId AND active = true ORDER BY created_at DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.comments WHERE submission_id = :submissionId AND active = true",
            nativeQuery = true)
    Page<Comment> findActiveCommentsBySubmissionId(@Param("submissionId") Long submissionId, Pageable pageable);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.comments WHERE submission_id = :submissionId AND active = true", nativeQuery = true)
    Long countActiveCommentsBySubmissionId(@Param("submissionId") Long submissionId);

    @Query(value = "SELECT * FROM challengesphere.comments WHERE id = :id AND active = true", nativeQuery = true)
    Optional<Comment> findActiveById(@Param("id") Long id);

    // Old queries for posts (kept for backward compatibility if needed)
    @Query(value = "SELECT * FROM challengesphere.comments WHERE post_id = :postId", nativeQuery = true)
    List<Comment> findByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT * FROM challengesphere.comments WHERE user_id = :userId", nativeQuery = true)
    List<Comment> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.comments WHERE post_id = :postId AND user_id = :userId", nativeQuery = true)
    List<Comment> findByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.comments WHERE active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Comment> findAllActiveCommentsOrderByCreatedAtDesc();

    @Query(value = "SELECT * FROM challengesphere.comments WHERE post_id = :postId AND active = true ORDER BY created_at ASC", nativeQuery = true)
    List<Comment> findActiveCommentsByPostIdOrderByCreatedAtAsc(@Param("postId") UUID postId);

    @Query(value = "SELECT * FROM challengesphere.comments WHERE user_id = :userId AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Comment> findActiveCommentsByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.comments WHERE content ILIKE %:searchTerm%", nativeQuery = true)
    List<Comment> searchCommentsByContent(@Param("searchTerm") String searchTerm);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.comments WHERE post_id = :postId AND active = true", nativeQuery = true)
    Long countActiveCommentsByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.comments WHERE user_id = :userId AND active = true", nativeQuery = true)
    Long countActiveCommentsByUserId(@Param("userId") Long userId);

    @Query(value = "UPDATE challengesphere.comments SET active = :active WHERE id = :id", nativeQuery = true)
    void updateCommentActiveStatus(@Param("id") Long id, @Param("active") Boolean active);

    @Query(value = "DELETE FROM challengesphere.comments WHERE post_id = :postId", nativeQuery = true)
    void deleteByPostId(@Param("postId") UUID postId);
}
