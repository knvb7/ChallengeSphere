package com.ironside.challengesphere.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ironside.challengesphere.model.Submission;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    // Feed query - followed users first, including private challenge posts only when the viewer is a participant.
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.active = true " +
            "AND c.active = true " +
            "AND (s.user_id = :userId OR EXISTS (" +
            "  SELECT 1 FROM challengesphere.follows f " +
            "  WHERE f.follower_id = :userId AND f.following_id = s.user_id AND f.active = true" +
            ")) " +
            "AND ((c.private = false AND s.is_private = false) OR (c.private = true AND EXISTS (" +
            "  SELECT 1 FROM challengesphere.user_challenges uc " +
            "  WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :userId AND uc.active = true" +
            "))) " +
            "ORDER BY s.created_at DESC, s.id DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
                    "WHERE s.active = true " +
                    "AND c.active = true " +
                    "AND (s.user_id = :userId OR EXISTS (" +
                    "  SELECT 1 FROM challengesphere.follows f " +
                    "  WHERE f.follower_id = :userId AND f.following_id = s.user_id AND f.active = true" +
                    ")) " +
                    "AND ((c.private = false AND s.is_private = false) OR (c.private = true AND EXISTS (" +
                    "  SELECT 1 FROM challengesphere.user_challenges uc " +
                    "  WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :userId AND uc.active = true" +
                    ")))",
            nativeQuery = true)
    Page<Submission> findFeedForUser(@Param("userId") Long userId, Pageable pageable);

    // Trending feed - public submissions sorted by engagement
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.active = true " +
            "AND c.active = true " +
            "AND s.is_private = false " +
            "AND s.created_at > NOW() - INTERVAL '7 days' " +
            "AND (s.user_id = :viewerId OR (" +
            "  EXISTS (SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = :viewerId AND f.following_id = s.user_id AND f.active = true) " +
            "  AND EXISTS (SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = s.user_id AND f.following_id = :viewerId AND f.active = true)" +
            ")) " +
            "ORDER BY (s.glories_count * 2 + s.comments_count) DESC, s.created_at DESC, s.id DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
                    "WHERE s.active = true " +
                    "AND c.active = true " +
                    "AND s.is_private = false " +
                    "AND s.created_at > NOW() - INTERVAL '7 days' " +
                    "AND (s.user_id = :viewerId OR (" +
                    "  EXISTS (SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = :viewerId AND f.following_id = s.user_id AND f.active = true) " +
                    "  AND EXISTS (SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = s.user_id AND f.following_id = :viewerId AND f.active = true)" +
                    "))",
            nativeQuery = true)
    Page<Submission> findTrendingSubmissions(@Param("viewerId") Long viewerId, Pageable pageable);

    // Explore feed - own/followed users first, then random users' public challenge submissions.
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.active = true " +
            "AND c.active = true " +
            "AND ((c.private = false AND s.is_private = false) OR (" +
            "  c.private = true " +
            "  AND (s.user_id = :viewerId OR EXISTS (SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = :viewerId AND f.following_id = s.user_id AND f.active = true)) " +
            "  AND EXISTS (SELECT 1 FROM challengesphere.user_challenges uc WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :viewerId AND uc.active = true)" +
            ")) " +
            "ORDER BY CASE WHEN s.user_id = :viewerId OR EXISTS (" +
            "  SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = :viewerId AND f.following_id = s.user_id AND f.active = true" +
            ") THEN 0 ELSE 1 END, s.created_at DESC, s.id DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
                    "WHERE s.active = true " +
                    "AND c.active = true " +
                    "AND ((c.private = false AND s.is_private = false) OR (" +
                    "  c.private = true " +
                    "  AND (s.user_id = :viewerId OR EXISTS (SELECT 1 FROM challengesphere.follows f WHERE f.follower_id = :viewerId AND f.following_id = s.user_id AND f.active = true)) " +
                    "  AND EXISTS (SELECT 1 FROM challengesphere.user_challenges uc WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :viewerId AND uc.active = true)" +
                    "))",
            nativeQuery = true)
    Page<Submission> findPublicSubmissions(@Param("viewerId") Long viewerId, Pageable pageable);

    // Find submission by user, challenge, and day
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId AND day_number = :dayNumber", nativeQuery = true)
    List<Submission> findByUserChallengeAndDay(@Param("userId") Long userId, @Param("challengeId") Long challengeId, @Param("dayNumber") Integer dayNumber);

    // Find all submissions by user and challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId ORDER BY day_number ASC", nativeQuery = true)
    List<Submission> findByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    // Paginated: all submissions by user and challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions WHERE user_id = :userId AND challenge_id = :challengeId",
            nativeQuery = true)
    Page<Submission> findByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId, Pageable pageable);

    // Find all active submissions by challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true ORDER BY submission_time DESC", nativeQuery = true)
    List<Submission> findActiveByChallengeId(@Param("challengeId") Long challengeId);

    // Paginated: active submissions by challenge
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true",
            nativeQuery = true)
    Page<Submission> findActiveByChallengeId(@Param("challengeId") Long challengeId, Pageable pageable);

    // Visible submissions for a challenge day; service layer shuffles only the returned page.
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.challenge_id = :challengeId " +
            "AND s.day_number = :dayNumber " +
            "AND s.active = true " +
            "AND c.active = true " +
            "AND (s.user_id = :viewerId OR ( " +
            "  NOT EXISTS (SELECT 1 FROM challengesphere.user_blocks ub WHERE ub.active = true AND ((ub.blocker_user_id = :viewerId AND ub.blocked_user_id = s.user_id) OR (ub.blocker_user_id = s.user_id AND ub.blocked_user_id = :viewerId))) " +
            "  AND ((c.private = false AND s.is_private = false) OR EXISTS (SELECT 1 FROM challengesphere.user_challenges uc WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :viewerId AND uc.active = true)) " +
            ")) " +
            "ORDER BY s.created_at DESC, s.id DESC",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
                    "WHERE s.challenge_id = :challengeId " +
                    "AND s.day_number = :dayNumber " +
                    "AND s.active = true " +
                    "AND c.active = true " +
                    "AND (s.user_id = :viewerId OR ( " +
                    "  NOT EXISTS (SELECT 1 FROM challengesphere.user_blocks ub WHERE ub.active = true AND ((ub.blocker_user_id = :viewerId AND ub.blocked_user_id = s.user_id) OR (ub.blocker_user_id = s.user_id AND ub.blocked_user_id = :viewerId))) " +
                    "  AND ((c.private = false AND s.is_private = false) OR EXISTS (SELECT 1 FROM challengesphere.user_challenges uc WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :viewerId AND uc.active = true)) " +
                    "))",
            nativeQuery = true)
    Page<Submission> findVisibleByChallengeIdAndDayNumber(
            @Param("challengeId") Long challengeId,
            @Param("dayNumber") Integer dayNumber,
            @Param("viewerId") Long viewerId,
            Pageable pageable);

    // Find all public active submissions by challenge
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.challenge_id = :challengeId " +
            "AND s.active = true " +
            "AND c.active = true " +
            "AND ((c.private = false AND s.is_private = false) OR (" +
            "  c.private = true " +
            "  AND EXISTS (SELECT 1 FROM challengesphere.user_challenges uc WHERE uc.challenge_id = s.challenge_id AND uc.user_id = :viewerId AND uc.active = true)" +
            ")) " +
            "ORDER BY s.created_at DESC", nativeQuery = true)
    List<Submission> findPublicActiveByChallengeId(@Param("challengeId") Long challengeId, @Param("viewerId") Long viewerId);

    // Find all active submissions by user
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.user_id = :userId AND s.active = true AND c.active = true " +
            "ORDER BY s.submission_time DESC", nativeQuery = true)
    List<Submission> findActiveByUserId(@Param("userId") Long userId);

    // Paginated: active submissions by user
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.user_id = :userId AND s.active = true AND c.active = true",
            countQuery = "SELECT COUNT(*) FROM challengesphere.submissions s " +
                    "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
                    "WHERE s.user_id = :userId AND s.active = true AND c.active = true",
            nativeQuery = true)
    Page<Submission> findActiveByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.submissions WHERE user_id = :userId AND active = true", nativeQuery = true)
    Integer countActiveByUserId(@Param("userId") Long userId);

    // Transaction-scoped advisory lock — used to serialize concurrent writers
    // for the same (user, challenge, day) tuple so the 5-per-day cap is atomic.
    // Lock is released automatically when the surrounding transaction commits or rolls back.
    @Query(value = "SELECT pg_advisory_xact_lock(:key)", nativeQuery = true)
    void acquireSubmissionDayLock(@Param("key") long key);

    // Find all submissions by user (including inactive)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findByUserId(@Param("userId") Long userId);

    // Find all public submissions by user
    @Query(value = "SELECT s.* FROM challengesphere.submissions s " +
            "JOIN challengesphere.challenges c ON c.id = s.challenge_id " +
            "WHERE s.user_id = :userId AND s.is_private = false AND s.active = true AND c.active = true " +
            "ORDER BY s.created_at DESC", nativeQuery = true)
    List<Submission> findPublicByUserId(@Param("userId") Long userId);

    // Find submission by ID and user (for authorization checks)
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE id = :submissionId AND user_id = :userId", nativeQuery = true)
    Optional<Submission> findByIdAndUserId(@Param("submissionId") Long submissionId, @Param("userId") Long userId);

    // Find all submissions for a specific date
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE DATE(submission_time) = :date AND active = true ORDER BY created_at DESC", nativeQuery = true)
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
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id = :challengeId AND active = true AND is_private = false ORDER BY glories_count DESC, created_at DESC LIMIT :limit", nativeQuery = true)
    List<Submission> findTrendingByChallengeId(@Param("challengeId") Long challengeId, @Param("limit") int limit);

    // Find all submissions by multiple challenge IDs
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE challenge_id IN :challengeIds AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<Submission> findByChallengeIdIn(@Param("challengeIds") List<Long> challengeIds);

    // Find submissions by user in a date range
    @Query(value = "SELECT * FROM challengesphere.submissions WHERE user_id = :userId AND DATE(submission_time) BETWEEN :startDate AND :endDate AND active = true ORDER BY submission_time DESC", nativeQuery = true)
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

    // Atomically increment glory count (avoids optimistic locking conflicts)
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE challengesphere.submissions SET glories_count = glories_count + 1, version = version + 1 WHERE id = :submissionId", nativeQuery = true)
    void incrementGloryCount(@Param("submissionId") Long submissionId);

    // Atomically decrement glory count (avoids optimistic locking conflicts)
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE challengesphere.submissions SET glories_count = GREATEST(0, glories_count - 1), version = version + 1 WHERE id = :submissionId", nativeQuery = true)
    void decrementGloryCount(@Param("submissionId") Long submissionId);

    // Atomically increment comment count (avoids optimistic locking conflicts)
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE challengesphere.submissions SET comments_count = comments_count + 1, version = version + 1 WHERE id = :submissionId", nativeQuery = true)
    void incrementCommentCount(@Param("submissionId") Long submissionId);

    // Atomically decrement comment count (avoids optimistic locking conflicts)
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE challengesphere.submissions SET comments_count = GREATEST(0, comments_count - 1), version = version + 1 WHERE id = :submissionId", nativeQuery = true)
    void decrementCommentCount(@Param("submissionId") Long submissionId);
}
