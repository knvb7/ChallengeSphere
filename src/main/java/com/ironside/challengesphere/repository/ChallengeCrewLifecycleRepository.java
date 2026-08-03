package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChallengeCrewLifecycleRepository extends JpaRepository<ChallengeCrewLifecycle, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT lifecycle FROM ChallengeCrewLifecycle lifecycle WHERE lifecycle.challengeId = :challengeId")
    Optional<ChallengeCrewLifecycle> findByChallengeIdForUpdate(@Param("challengeId") Long challengeId);

    Optional<ChallengeCrewLifecycle> findByRematchOfChallengeId(Long challengeId);
}
