package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeCrewInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeCrewInviteRepository extends JpaRepository<ChallengeCrewInvite, Long> {
    List<ChallengeCrewInvite> findByChallengeId(Long challengeId);

    Optional<ChallengeCrewInvite> findByChallengeIdAndInvitedUserId(Long challengeId, Long invitedUserId);

    @Modifying
    @Query(value = "DELETE FROM challengesphere.challenge_crew_invites WHERE challenge_id = :challengeId", nativeQuery = true)
    int deleteAllByChallengeId(@Param("challengeId") Long challengeId);

    @Modifying
    @Query(value = "DELETE FROM challengesphere.challenge_crew_invites WHERE challenge_id = :challengeId AND invited_user_id = :invitedUserId", nativeQuery = true)
    int deleteByChallengeIdAndInvitedUserId(
            @Param("challengeId") Long challengeId,
            @Param("invitedUserId") Long invitedUserId);
}
