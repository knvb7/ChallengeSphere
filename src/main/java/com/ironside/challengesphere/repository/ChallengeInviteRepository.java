package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeInviteRepository extends JpaRepository<ChallengeInvite, Long> {

    @Query(value = "SELECT * FROM challengesphere.challenge_invites WHERE invite_code = :inviteCode", nativeQuery = true)
    Optional<ChallengeInvite> findByInviteCode(@Param("inviteCode") String inviteCode);

    @Query(value = "SELECT * FROM challengesphere.challenge_invites WHERE invite_code = :inviteCode FOR UPDATE", nativeQuery = true)
    Optional<ChallengeInvite> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);

    @Query(value = "SELECT * FROM challengesphere.challenge_invites WHERE challenge_id = :challengeId AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<ChallengeInvite> findActiveByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.challenge_invites WHERE challenge_id = :challengeId ORDER BY created_at DESC", nativeQuery = true)
    List<ChallengeInvite> findByChallengeId(@Param("challengeId") Long challengeId);

    @Query(value = "SELECT * FROM challengesphere.challenge_invites WHERE created_by = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<ChallengeInvite> findByCreatedBy(@Param("userId") Long userId);

    @Modifying
    @Query(value = "UPDATE challengesphere.challenge_invites SET active = false WHERE challenge_id = :challengeId AND active = true", nativeQuery = true)
    int deactivateActiveByChallengeId(@Param("challengeId") Long challengeId);

    @Modifying
    @Query(value = "UPDATE challengesphere.challenge_invites SET active = false WHERE challenge_id = :challengeId AND recipient_user_id IS NULL AND active = true", nativeQuery = true)
    int deactivateUnboundActiveByChallengeId(@Param("challengeId") Long challengeId);

    @Modifying
    @Query(value = "UPDATE challengesphere.challenge_invites SET active = false WHERE challenge_id = :challengeId AND recipient_user_id = :recipientUserId AND active = true", nativeQuery = true)
    int deactivateActiveByChallengeIdAndRecipientUserId(
            @Param("challengeId") Long challengeId,
            @Param("recipientUserId") Long recipientUserId);
}
