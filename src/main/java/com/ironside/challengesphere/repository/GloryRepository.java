package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Glory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GloryRepository extends JpaRepository<Glory, Long> {

    @Query(value = "SELECT * FROM challengesphere.glories WHERE submission_id = :submissionId AND user_id = :userId", nativeQuery = true)
    Optional<Glory> findBySubmissionIdAndUserId(@Param("submissionId") Long submissionId, @Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.glories WHERE submission_id = :submissionId AND active = true", nativeQuery = true)
    List<Glory> findActiveBySubmissionId(@Param("submissionId") Long submissionId);

    @Query(value = "SELECT * FROM challengesphere.glories WHERE submission_id = :submissionId AND active = true",
            countQuery = "SELECT COUNT(*) FROM challengesphere.glories WHERE submission_id = :submissionId AND active = true",
            nativeQuery = true)
    Page<Glory> findActiveBySubmissionId(@Param("submissionId") Long submissionId, Pageable pageable);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.glories WHERE submission_id = :submissionId AND active = true", nativeQuery = true)
    Long countActiveBySubmissionId(@Param("submissionId") Long submissionId);

    @Query(value = "SELECT * FROM challengesphere.glories WHERE user_id = :userId AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Glory> findActiveByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.glories WHERE submission_id = :submissionId AND user_id = :userId AND active = true)", nativeQuery = true)
    boolean existsBySubmissionIdAndUserId(@Param("submissionId") Long submissionId, @Param("userId") Long userId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.glories WHERE submission_id = :submissionId AND user_id = :userId AND ungloried = true)", nativeQuery = true)
    boolean existsUngloriedBySubmissionIdAndUserId(@Param("submissionId") Long submissionId, @Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.glories WHERE submission_id = :submissionId AND ungloried = true", nativeQuery = true)
    Long countUngloriedBySubmissionId(@Param("submissionId") Long submissionId);
}