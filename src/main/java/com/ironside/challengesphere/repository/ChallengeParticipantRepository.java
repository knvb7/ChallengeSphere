package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeParticipantRepository extends JpaRepository<ChallengeParticipant, Long> {
    
    List<ChallengeParticipant> findByUserId(Long userId);
    
    List<ChallengeParticipant> findByUserIdAndIsAcceptedTrue(Long userId);
    
    List<ChallengeParticipant> findByChallengeId(Long challengeId);
    
    List<ChallengeParticipant> findByChallengeIdAndIsAcceptedTrue(Long challengeId);
    
    Optional<ChallengeParticipant> findByUserIdAndChallengeId(Long userId, Long challengeId);
    
    boolean existsByUserIdAndChallengeId(Long userId, Long challengeId);
    
    boolean existsByUserIdAndChallengeIdAndIsAcceptedTrue(Long userId, Long challengeId);
    
    int countByChallengeIdAndIsAcceptedTrue(Long challengeId);
    
    void deleteByUserIdAndChallengeId(Long userId, Long challengeId);
}