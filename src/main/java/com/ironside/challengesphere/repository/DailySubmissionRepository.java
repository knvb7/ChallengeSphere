package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.DailySubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailySubmissionRepository extends JpaRepository<DailySubmission, Long> {
    
    List<DailySubmission> findByParticipantId(Long participantId);
    
    @Query("SELECT COUNT(d) FROM DailySubmission d WHERE d.participant.id = :participantId")
    Long countByParticipantId(Long participantId);
    
    @Query("SELECT COUNT(d) FROM DailySubmission d WHERE d.participant.id = :participantId AND d.isVerified = true")
    Long countByParticipantIdAndIsVerifiedTrue(Long participantId);
    
    List<DailySubmission> findByParticipantChallengeId(Long challengeId);
    
    List<DailySubmission> findByParticipantChallengeIdAndSubmissionDate(Long challengeId, LocalDate submissionDate);
    
    Optional<DailySubmission> findByParticipantIdAndSubmissionDate(Long participantId, LocalDate submissionDate);
    
    @Query("SELECT d.submissionDate FROM DailySubmission d WHERE d.participant.id = :participantId ORDER BY d.submissionDate DESC")
    List<LocalDate> findSubmissionDatesByParticipantIdOrderBySubmissionDateDesc(Long participantId);
    
    @Query("SELECT d.submissionDate FROM DailySubmission d WHERE d.participant.id = :participantId ORDER BY d.submissionDate ASC")
    List<LocalDate> findSubmissionDatesByParticipantIdOrderBySubmissionDate(Long participantId);
}