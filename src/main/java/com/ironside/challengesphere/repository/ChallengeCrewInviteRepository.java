package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeCrewInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeCrewInviteRepository extends JpaRepository<ChallengeCrewInvite, Long> {
    List<ChallengeCrewInvite> findByChallengeId(Long challengeId);

    Optional<ChallengeCrewInvite> findByChallengeIdAndInvitedUserId(Long challengeId, Long invitedUserId);
}
