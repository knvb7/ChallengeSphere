package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserChallengeMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserChallengeMilestoneRepository extends JpaRepository<UserChallengeMilestone, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO challengesphere.user_challenge_milestones
                (user_id, challenge_id, milestone_code)
            VALUES (:userId, :challengeId, :milestoneCode)
            ON CONFLICT (user_id, challenge_id, milestone_code) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("userId") Long userId,
            @Param("challengeId") Long challengeId,
            @Param("milestoneCode") String milestoneCode);

    @Modifying
    @Query(value = """
            UPDATE challengesphere.user_challenge_milestones
            SET notification_id = :notificationId
            WHERE user_id = :userId
              AND challenge_id = :challengeId
              AND milestone_code = :milestoneCode
            """, nativeQuery = true)
    int attachNotification(
            @Param("userId") Long userId,
            @Param("challengeId") Long challengeId,
            @Param("milestoneCode") String milestoneCode,
            @Param("notificationId") Long notificationId);
}
