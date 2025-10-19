package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserChallengeRepository extends JpaRepository<UserChallenge, Long> {

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId AND challenge_id = :challengeId", nativeQuery = true)
    Optional<UserChallenge> findByUserIdAndChallengeId(@Param("userId") Long userId, @Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id = :challengeId", nativeQuery = true)
    List<UserChallenge> findByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId", nativeQuery = true)
    List<UserChallenge> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE user_id = :userId AND active = true", nativeQuery = true)
    List<UserChallenge> findActiveByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id = :challengeId AND active = true", nativeQuery = true)
    List<UserChallenge> findActiveByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id IN :challengeIds AND active = true", nativeQuery = true)
    List<UserChallenge> findActiveByChallengeIds(@Param("challengeIds") List<Long> challengeIds);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id IN :challengeIds", nativeQuery = true)
    List<UserChallenge> findByChallengeIds(List<Long> challengeIds);

    @Query(value = "SELECT * FROM challengesphere.user_challenges WHERE challenge_id IN :challengeIds", nativeQuery = true)
    List<UserChallenge> findByChallengeIdIn(@Param("challengeIds") List<Long> challengeIds);
}