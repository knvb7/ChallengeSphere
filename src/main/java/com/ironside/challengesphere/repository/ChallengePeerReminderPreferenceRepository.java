package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengePeerReminderPreference;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengePeerReminderPreferenceRepository
        extends JpaRepository<ChallengePeerReminderPreference, Long> {

    Optional<ChallengePeerReminderPreference> findByChallengeIdAndUserId(Long challengeId, Long userId);

    List<ChallengePeerReminderPreference> findByChallengeIdAndEnabledTrue(Long challengeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT preference
            FROM ChallengePeerReminderPreference preference
            WHERE preference.challengeId = :challengeId AND preference.userId = :userId
            """)
    Optional<ChallengePeerReminderPreference> findByChallengeIdAndUserIdForUpdate(
            @Param("challengeId") Long challengeId,
            @Param("userId") Long userId
    );
}
