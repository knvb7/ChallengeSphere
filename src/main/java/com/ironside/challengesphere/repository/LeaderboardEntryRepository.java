package com.ironside.challengesphere.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ironside.challengesphere.model.LeaderboardEntry;
import com.ironside.challengesphere.model.LeaderboardEntry.LeaderboardStatus;

/**
 * Repository for LeaderboardEntry persistence
 * Provides efficient queries for leaderboard operations
 */
@Repository
public interface LeaderboardEntryRepository extends JpaRepository<LeaderboardEntry, Long> {

    // ==================== BASIC QUERIES ====================

    /**
     * Find entry by challenge and user
     */
    Optional<LeaderboardEntry> findByChallengeIdAndUserId(Long challengeId, Long userId);

    /**
     * Check if user has entry in leaderboard
     */
    boolean existsByChallengeIdAndUserId(Long challengeId, Long userId);

    /**
     * Find all entries for a challenge (for rebuilding Redis cache)
     */
    List<LeaderboardEntry> findByChallengeIdAndStatusOrderByScoreDesc(Long challengeId, LeaderboardStatus status);

    /**
     * Find all active entries for a challenge
     */
    default List<LeaderboardEntry> findActiveByChallengeId(Long challengeId) {
        return findByChallengeIdAndStatusOrderByScoreDesc(challengeId, LeaderboardStatus.ACTIVE);
    }

    // ==================== TOP N QUERIES ====================

    /**
     * Get top N entries for a challenge by score (descending)
     */
    @Query("SELECT le FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE' " +
           "ORDER BY le.score DESC")
    List<LeaderboardEntry> findTopByChallengeId(@Param("challengeId") Long challengeId, Pageable pageable);

    /**
     * Get top N entries with user details (optimized fetch)
     */
    @Query("SELECT le FROM LeaderboardEntry le " +
           "JOIN FETCH le.user " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE' " +
           "ORDER BY le.score DESC")
    List<LeaderboardEntry> findTopWithUserByChallengeId(@Param("challengeId") Long challengeId, Pageable pageable);

    // ==================== RANKING QUERIES ====================

    /**
     * Get user's rank in a challenge (1-based)
     */
    @Query("SELECT COUNT(le) + 1 FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId " +
           "AND le.status = 'ACTIVE' " +
           "AND le.score > (SELECT le2.score FROM LeaderboardEntry le2 " +
           "                WHERE le2.challenge.id = :challengeId AND le2.user.id = :userId)")
    Integer getUserRank(@Param("challengeId") Long challengeId, @Param("userId") Long userId);

    /**
     * Get entries around a specific user (for context)
     * Returns users ranked above and below the target user
     */
    @Query(value = "WITH ranked AS (" +
           "  SELECT *, ROW_NUMBER() OVER (ORDER BY score DESC) as rn " +
           "  FROM leaderboard_entries " +
           "  WHERE challenge_id = :challengeId AND status = 'ACTIVE'" +
           "), user_rank AS (" +
           "  SELECT rn FROM ranked WHERE user_id = :userId" +
           ") " +
           "SELECT r.* FROM ranked r, user_rank ur " +
           "WHERE r.rn BETWEEN ur.rn - :above AND ur.rn + :below " +
           "ORDER BY r.rn",
           nativeQuery = true)
    List<LeaderboardEntry> findEntriesAroundUser(
            @Param("challengeId") Long challengeId,
            @Param("userId") Long userId,
            @Param("above") int above,
            @Param("below") int below);

    // ==================== PAGINATION ====================

    /**
     * Get paginated leaderboard
     */
    @Query("SELECT le FROM LeaderboardEntry le " +
           "JOIN FETCH le.user " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE' " +
           "ORDER BY le.score DESC")
    Page<LeaderboardEntry> findByChallengeIdPaginated(@Param("challengeId") Long challengeId, Pageable pageable);

    // ==================== STATISTICS ====================

    /**
     * Count total participants in a challenge leaderboard
     */
    @Query("SELECT COUNT(le) FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE'")
    Long countParticipants(@Param("challengeId") Long challengeId);

    /**
     * Get average score for a challenge
     */
    @Query("SELECT AVG(le.score) FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE'")
    Double getAverageScore(@Param("challengeId") Long challengeId);

    /**
     * Get max score for a challenge
     */
    @Query("SELECT MAX(le.score) FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE'")
    Double getMaxScore(@Param("challengeId") Long challengeId);

    /**
     * Get total submissions across all participants
     */
    @Query("SELECT SUM(le.submissionCount) FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE'")
    Long getTotalSubmissions(@Param("challengeId") Long challengeId);

    // ==================== USER-SPECIFIC QUERIES ====================

    /**
     * Find all leaderboard entries for a user across all challenges
     */
    List<LeaderboardEntry> findByUserIdAndStatusOrderByScoreDesc(Long userId, LeaderboardStatus status);

    /**
     * Find user's active leaderboard entries
     */
    default List<LeaderboardEntry> findActiveByUserId(Long userId) {
        return findByUserIdAndStatusOrderByScoreDesc(userId, LeaderboardStatus.ACTIVE);
    }

    /**
     * Get user's best rankings across all challenges
     */
    @Query("SELECT le FROM LeaderboardEntry le " +
           "JOIN FETCH le.challenge " +
           "WHERE le.user.id = :userId AND le.status = 'ACTIVE' " +
           "ORDER BY le.currentRank ASC NULLS LAST")
    List<LeaderboardEntry> findUserBestRankings(@Param("userId") Long userId, Pageable pageable);

    // ==================== BULK OPERATIONS ====================

    /**
     * Update ranks for all entries in a challenge
     * Call this after scores change to recalculate ranks
     */
    @Modifying
    @Query(value = "UPDATE leaderboard_entries le " +
           "SET previous_rank = current_rank, " +
           "    current_rank = ranked.rn, " +
           "    updated_at = NOW() " +
           "FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY score DESC) as rn " +
           "      FROM leaderboard_entries " +
           "      WHERE challenge_id = :challengeId AND status = 'ACTIVE') ranked " +
           "WHERE le.id = ranked.id AND le.challenge_id = :challengeId",
           nativeQuery = true)
    void updateRanksForChallenge(@Param("challengeId") Long challengeId);

    /**
     * Delete all entries for a challenge (when challenge is deleted)
     */
    @Modifying
    @Query("DELETE FROM LeaderboardEntry le WHERE le.challenge.id = :challengeId")
    void deleteByChallengeId(@Param("challengeId") Long challengeId);

    /**
     * Disqualify a user from a challenge
     */
    @Modifying
    @Query("UPDATE LeaderboardEntry le SET le.status = 'DISQUALIFIED', le.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE le.challenge.id = :challengeId AND le.user.id = :userId")
    void disqualifyUser(@Param("challengeId") Long challengeId, @Param("userId") Long userId);

    // ==================== CHALLENGE COMPLETION ====================

    /**
     * Mark all active entries as completed when challenge ends
     */
    @Modifying
    @Query("UPDATE LeaderboardEntry le SET le.status = 'COMPLETED', le.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE le.challenge.id = :challengeId AND le.status = 'ACTIVE'")
    void markChallengeCompleted(@Param("challengeId") Long challengeId);

    // ==================== SYNC WITH REDIS ====================

    /**
     * Get all entries that need to be synced to Redis
     * (entries updated after a certain time)
     */
    @Query("SELECT le FROM LeaderboardEntry le " +
           "WHERE le.challenge.id = :challengeId " +
           "AND le.status = 'ACTIVE' " +
           "ORDER BY le.score DESC")
    List<LeaderboardEntry> findEntriesForRedisSync(@Param("challengeId") Long challengeId);
}
