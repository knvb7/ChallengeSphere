package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.util.ChallengeStatus;
import com.ironside.challengesphere.util.ChallengeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    List<Challenge> findByCreatorUserId(Long creatorUserId);
    
    List<Challenge> findByChallengeType(ChallengeType challengeType);
    
    List<Challenge> findByStatus(ChallengeStatus status);
    
    Optional<Challenge> findByInviteCode(String inviteCode);
    
    @Query("SELECT c FROM Challenge c WHERE c.endDate >= :currentDate AND c.status = 'ACTIVE'")
    List<Challenge> findActiveAndOngoingChallenges(LocalDate currentDate);
    
    @Query("SELECT c FROM Challenge c WHERE c.startDate <= :currentDate AND c.endDate >= :currentDate AND c.status = 'ACTIVE'")
    List<Challenge> findActiveChallengesInProgress(LocalDate currentDate);
}
