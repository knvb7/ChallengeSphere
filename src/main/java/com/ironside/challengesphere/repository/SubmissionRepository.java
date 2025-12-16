package com.ironside.challengesphere.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ironside.challengesphere.model.Submission;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    // Feed query - get submissions from users that the current user follows
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "INNER JOIN challengesphere.follows f ON s.user_id = f.following_id " +
            "WHERE f.follower_id = :userId " +
            "AND f.active = true " +
            "AND s.active = true " +
            "AND s.is_private = false " +
            "ORDER BY s.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "INNER JOIN challengesphere.follows f ON s.user_id = f.following_id " +
                    "WHERE f.follower_id = :userId " +
                    "AND f.active = true " +
                    "AND s.active = true " +
                    "AND s.is_private = false",
            nativeQuery = true)
    Page<Submission> findFeedForUser(@Param("userId") Long userId, Pageable pageable);

    // Trending feed - public submissions sorted by engagement
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "WHERE s.active = true " +
            "AND s.is_private = false " +
            "AND s.created_at > NOW() - INTERVAL '7 days' " +
            "ORDER BY (s.glories_count * 2 + s.comments_count) DESC, s.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "WHERE s.active = true " +
                    "AND s.is_private = false " +
                    "AND s.created_at > NOW() - INTERVAL '7 days'",
            nativeQuery = true)
    Page<Submission> findTrendingSubmissions(Pageable pageable);

    // Explore feed - all public submissions sorted by recency
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "WHERE s.active = true " +
            "AND s.is_private = false " +
            "ORDER BY s.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "WHERE s.active = true " +
                    "AND s.is_private = false",
            nativeQuery = true)
    Page<Submission> findPublicSubmissions(Pageable pageable);

    // Find submission by user, challenge, and day
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId AND day_number = :dayNumber", nativeQuery = true)
    List<Submission> findByUserChallengeAndDay(@Param("userId") Long userId, @Param("challengeId") Long challengeId, @Param("dayNumber") Integer dayNumber);

    // Find all submissions by user and challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId ORDER BY day_number ASC", nativeQuery = true)
    List<Submission> findByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    // Find all active submissions by challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true ORDER BY submission_time DESC", nativeQuery = true)
    List<Submission> findActiveByChallengeId(@Param("challengeId") Long challengeId);

    // Find all public active submissions by challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true AND is_private = false ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findPublicActiveByChallengeId(@Param("challengeId") Long challengeId);

    // Find all active submissions by user
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND active = true ORDER BY submission_time DESC", nativeQuery = true)
    List<Submission> findActiveByUserId(@Param("userId") Long userId);

    // Find all submissions by user (including inactive)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findByUserId(@Param("userId") Long userId);

    // Find all public submissions by user
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND is_private = false AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findPublicByUserId(@Param("userId") Long userId);

    // Find submission by ID and user (for authorization checks)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE id = :submissionId AND user_id = :userId", nativeQuery = true)
    Optional<Submission> findByIdAndUserId(@Param("submissionId") Long submissionId, @Param("userId") Long userId);

    // Find all submissions for a specific date
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE submission_date = :date AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findBySubmissionDate(@Param("date") LocalDate date);

    // Find recent submissions by challenge (limit)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true AND is_private = false ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<Submission> findRecentByChallengeId(@Param("challengeId") Long challengeId, @Param("limit") int limit);

    // Count active submissions by user and challenge
    @Query(value = "SELECT COUNT(*) FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId AND active = true", nativeQuery = true)
    Long countActiveByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    // Count total submissions by challenge
    @Query(value = "SELECT COUNT(*) FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true", nativeQuery = true)
    Long countActiveByChallengeId(@Param("challengeId") Long challengeId);

    // Find submissions with most glories (trending)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true AND is_private = false ORDER BY glory_count DESC, created_at DESC LIMIT :limit", nativeQuery = true)
    List<Submission> findTrendingByChallengeId(@Param("challengeId") Long challengeId, @Param("limit") int limit);

    // Find all submissions by multiple challenge IDs
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id IN :challengeIds AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findByChallengeIdIn(@Param("challengeIds") List<Long> challengeIds);

    // Find submissions by user in a date range
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND submission_date BETWEEN :startDate AND :endDate AND active = true ORDER BY submission_date DESC", nativeQuery = true)
    List<Submission> findByUserIdAndDateRange(@Param("userId") Long userId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Check if submission exists for a specific user, challenge, and day
    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId AND day_number = :dayNumber)", nativeQuery = true)
    boolean existsByUserChallengeAndDay(@Param("userId") Long userId, @Param("challengeId") Long challengeId, @Param("dayNumber") Integer dayNumber);

    // Find all submissions for a challenge by a specific user
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId AND active = true ORDER BY day_number ASC", nativeQuery = true)
    List<Submission> findActiveByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    // Find submissions by challenge and user (for leaderboard)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND user_id = :userId ORDER BY day_number ASC", nativeQuery = true)
    List<Submission> findByChallengeIdAndUserId(@Param("challengeId") Long challengeId, @Param("userId") Long userId);

    // Find submissions by challenge for multiple users (for leaderboard batch fetch)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND user_id IN :userIds AND active = true", nativeQuery = true)
    List<Submission> findByChallengeIdAndUserIdIn(@Param("challengeId") Long challengeId, @Param("userIds") List<Long> userIds);
}
