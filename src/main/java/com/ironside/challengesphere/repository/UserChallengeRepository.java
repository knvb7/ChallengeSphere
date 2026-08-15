package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserChallengeRepository extends JpaRepository<UserChallenge, Long> {

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId AND challenge_id = :challengeId", nativeQuery = true)
    Optional<UserChallenge> findByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT uc FROM UserChallenge uc WHERE uc.userId = :userId AND uc.challengeId = :challengeId")
    Optional<UserChallenge> findByUserIdAndChallengeIdForUpdate(
            @Param("userId") Long userId,
            @Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id = :challengeId", nativeQuery = true)
    List<UserChallenge> findByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId", nativeQuery = true)
    List<UserChallenge> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId AND challenge_id IN :challengeIds", nativeQuery = true)
    List<UserChallenge> findByUserIdAndChallengeIds(
            @Param("userId") Long userId,
            @Param("challengeIds") List<Long> challengeIds);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId AND active = true AND COALESCE(banned, false) = false", nativeQuery = true)
    List<UserChallenge> findActiveByUserId(@Param("userId") Long userId);

    @Query(value = """
            SELECT *
            FROM challengesphere.user_challenges
            WHERE user_id = :userId
              AND favorite = true
              AND COALESCE(banned, false) = false
            ORDER BY favorited_at DESC NULLS LAST, joined_at DESC, id DESC
            """, nativeQuery = true)
    List<UserChallenge> findFavoritesByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id = :challengeId AND active = true AND COALESCE(banned, false) = false", nativeQuery = true)
    List<UserChallenge> findActiveByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id IN :challengeIds AND active = true AND COALESCE(banned, false) = false", nativeQuery = true)
    List<UserChallenge> findActiveByChallengeIds(@Param("challengeIds") List<Long> challengeIds);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id IN :challengeIds", nativeQuery = true)
    List<UserChallenge> findByChallengeIds(List<Long> challengeIds);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id IN :challengeIds", nativeQuery = true)
    List<UserChallenge> findByChallengeIdIn(@Param("challengeIds") List<Long> challengeIds);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.user_challenges WHERE challenge_id = :challengeId AND active = true AND COALESCE(banned, false) = false", nativeQuery = true)
    int countActiveByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = """
            SELECT COUNT(*)
            FROM challengesphere.user_challenges uc
            JOIN challengesphere.challenges c ON c.id = uc.challenge_id
            WHERE uc.user_id = :userId
              AND uc.active = true
              AND COALESCE(uc.banned, false) = false
              AND COALESCE(uc.completed, false) = false
              AND c.active = true
              AND (
                    UPPER(COALESCE(c.challenge_mode, 'DAILY')) = 'ONE_OFF'
                    OR CURRENT_DATE < (c.start_date + (c.number_of_days * INTERVAL '1 day'))::date
              )
            """, nativeQuery = true)
    long countSlotConsumingParticipations(@Param("userId") Long userId);

    @Query(value = """
            SELECT COUNT(*)
            FROM challengesphere.user_challenges uc
            JOIN challengesphere.challenges c ON c.id = uc.challenge_id
            WHERE uc.user_id = :userId
              AND uc.active = true
              AND COALESCE(uc.banned, false) = false
              AND COALESCE(uc.completed, false) = false
              AND c.active = true
              AND c.private = :privateChallenge
              AND (
                    UPPER(COALESCE(c.challenge_mode, 'DAILY')) = 'ONE_OFF'
                    OR CURRENT_DATE < (c.start_date + (c.number_of_days * INTERVAL '1 day'))::date
              )
            """, nativeQuery = true)
    long countSlotConsumingParticipationsByVisibility(
            @Param("userId") Long userId,
            @Param("privateChallenge") boolean privateChallenge);
}
