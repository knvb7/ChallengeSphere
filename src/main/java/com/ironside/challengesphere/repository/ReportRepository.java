package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {

    @Query(value = "SELECT * FROM challengesphere.reports WHERE post_id = :postId", nativeQuery = true)
    List<Report> findByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT * FROM challengesphere.reports WHERE user_id = :userId", nativeQuery = true)
    List<Report> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.reports WHERE status = :status", nativeQuery = true)
    List<Report> findByStatus(@Param("status") String status);

    @Query(value = "SELECT * FROM challengesphere.reports WHERE reason = :reason", nativeQuery = true)
    List<Report> findByReason(@Param("reason") String reason);

    @Query(value = "SELECT * FROM challengesphere.reports WHERE post_id = :postId AND user_id = :userId", nativeQuery = true)
    List<Report> findByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.reports WHERE active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Report> findAllActiveReportsOrderByCreatedAtDesc();

    @Query(value = "SELECT * FROM challengesphere.reports WHERE status = 'PENDING' AND active = true ORDER BY created_at ASC", nativeQuery = true)
    List<Report> findPendingReportsOrderByCreatedAtAsc();

    @Query(value = "SELECT * FROM challengesphere.reports WHERE status = 'REVIEWED' ORDER BY created_at DESC", nativeQuery = true)
    List<Report> findReviewedReports();

    @Query(value = "SELECT COUNT(*) FROM challengesphere.reports WHERE post_id = :postId AND active = true", nativeQuery = true)
    Long countActiveReportsByPostId(@Param("postId") UUID postId);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.reports WHERE status = :status", nativeQuery = true)
    Long countReportsByStatus(@Param("status") String status);

    @Modifying
    @Query(value = "UPDATE challengesphere.reports SET status = :status WHERE id = :id", nativeQuery = true)
    void updateReportStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query(value = "UPDATE challengesphere.reports SET active = :active WHERE id = :id", nativeQuery = true)
    void updateReportActiveStatus(@Param("id") Long id, @Param("active") Boolean active);
}
