package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.ParticipantResponse;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeParticipant;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeParticipantRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.DailySubmissionRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ParticipationService;
import com.ironside.challengesphere.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ParticipationServiceImpl implements ParticipationService {

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final ChallengeParticipantRepository participantRepository;
    private final DailySubmissionRepository submissionRepository;
    private final UserService userService;

    @Override
    @Transactional
    public void joinChallenge(Long userId, Long challengeId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with id: " + challengeId));
        
        // Check if user is already participating
        if (participantRepository.existsByUserIdAndChallengeId(userId, challengeId)) {
            throw new IllegalStateException("User is already participating in this challenge");
        }
        
        // Create participation record
        ChallengeParticipant participant = new ChallengeParticipant();
        participant.setUser(user);
        participant.setChallenge(challenge);
        participant.setJoinedAt(OffsetDateTime.now());
        participant.setIsAccepted(true);
        
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void joinChallengeByInviteCode(Long userId, String inviteCode) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));
        
        Challenge challenge = challengeRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with invite code: " + inviteCode));
        
        // Check if user is already participating
        if (participantRepository.existsByUserIdAndChallengeId(userId, challenge.getChallengeId())) {
            throw new IllegalStateException("User is already participating in this challenge");
        }
        
        // Create participation record
        ChallengeParticipant participant = new ChallengeParticipant();
        participant.setUser(user);
        participant.setChallenge(challenge);
        participant.setJoinedAt(OffsetDateTime.now());
        participant.setIsAccepted(true);
        
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void leaveChallenge(Long userId, Long challengeId) {
        ChallengeParticipant participant = participantRepository.findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Participant not found"));
        
        // Delete participation
        participantRepository.delete(participant);
    }

    @Override
    public List<ParticipantResponse> getChallengeParticipants(Long challengeId) {
        // Check if challenge exists
        if (!challengeRepository.existsById(challengeId)) {
            throw new EntityNotFoundException("Challenge not found with id: " + challengeId);
        }
        
        // Get all participants
        List<ChallengeParticipant> participants = participantRepository.findByChallengeIdAndIsAcceptedTrue(challengeId);
        
        return participants.stream()
                .map(this::mapToParticipantResponse)
                .collect(Collectors.toList());
    }

    @Override
    public Double getUserProgress(Long userId, Long challengeId) {
        // Get challenge
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with id: " + challengeId));
        
        // Check if user is participating
        ChallengeParticipant participant = participantRepository.findByUserIdAndChallengeId(userId, challengeId)
                .orElseThrow(() -> new EntityNotFoundException("User is not participating in this challenge"));
        
        // Calculate the total days of the challenge
        long totalDays = ChronoUnit.DAYS.between(challenge.getStartDate(), challenge.getEndDate()) + 1;
        
        // Calculate days that have passed so far (but not exceeding the total)
        LocalDate today = LocalDate.now();
        long daysPassed;
        
        if (today.isBefore(challenge.getStartDate())) {
            // Challenge hasn't started yet
            return 0.0;
        } else if (today.isAfter(challenge.getEndDate())) {
            // Challenge has ended
            daysPassed = totalDays;
        } else {
            // Challenge is ongoing
            daysPassed = ChronoUnit.DAYS.between(challenge.getStartDate(), today) + 1;
        }
        
        // Get the number of submissions for this participant
        long submissionCount = submissionRepository.countByParticipantId(participant.getParticipantId());
        
        // Calculate progress as a percentage of days covered with submissions
        double progress = (submissionCount / (double) daysPassed) * 100;
        
        // Cap at 100%
        return Math.min(progress, 100.0);
    }

    @Override
    public List<ParticipantResponse> getChallengeLeaderboard(Long challengeId) {
        // Check if challenge exists
        if (!challengeRepository.existsById(challengeId)) {
            throw new EntityNotFoundException("Challenge not found with id: " + challengeId);
        }
        
        // Get all participants
        List<ChallengeParticipant> participants = participantRepository.findByChallengeIdAndIsAcceptedTrue(challengeId);
        
        // Map to response objects and add metrics
        List<ParticipantResponse> participantResponses = participants.stream()
                .map(this::mapToParticipantResponse)
                .collect(Collectors.toList());
        
        // Sort by completion percentage (descending) and then by verified submissions (descending)
        return participantResponses.stream()
                .sorted(Comparator.comparing(ParticipantResponse::getCompletionPercentage).reversed()
                        .thenComparing(Comparator.comparing(ParticipantResponse::getVerifiedSubmissionsCount).reversed()))
                .collect(Collectors.toList());
    }
    
    /**
     * Maps a ChallengeParticipant entity to a ParticipantResponse
     */
    private ParticipantResponse mapToParticipantResponse(ChallengeParticipant participant) {
        ParticipantResponse response = new ParticipantResponse();
        
        response.setParticipantId(participant.getParticipantId());
        
        // Get user profile
        UserProfileResponse userProfile = userService.getUserProfile(participant.getUser().getUserId());
        response.setUser(userProfile);
        
        response.setJoinedAt(participant.getJoinedAt());
        
        // Get submission metrics
        int submissionsCount = submissionRepository.countByParticipantId(participant.getParticipantId()).intValue();
        response.setSubmissionsCount(submissionsCount);
        
        int verifiedSubmissionsCount = submissionRepository
                .countByParticipantIdAndIsVerifiedTrue(participant.getParticipantId()).intValue();
        response.setVerifiedSubmissionsCount(verifiedSubmissionsCount);
        
        // Calculate completion percentage
        Challenge challenge = participant.getChallenge();
        long totalDays = ChronoUnit.DAYS.between(challenge.getStartDate(), challenge.getEndDate()) + 1;
        double completionPercentage = (verifiedSubmissionsCount / (double) totalDays) * 100;
        response.setCompletionPercentage(Math.min(completionPercentage, 100.0));
        
        // Calculate streaks (simplified implementation)
        // In a real app, this would involve more complex logic to find consecutive submissions
        response.setCurrentStreak(calculateCurrentStreak(participant));
        response.setLongestStreak(calculateLongestStreak(participant));
        
        return response;
    }
    
    /**
     * Calculates the current streak for a participant (simplified)
     */
    private int calculateCurrentStreak(ChallengeParticipant participant) {
        // This is a simplified implementation
        // In a real app, we would look at consecutive daily submissions
        
        Challenge challenge = participant.getChallenge();
        
        // If challenge hasn't started yet, streak is 0
        if (LocalDate.now().isBefore(challenge.getStartDate())) {
            return 0;
        }
        
        // Get recent submissions ordered by date (descending)
        List<LocalDate> submissionDates = submissionRepository
                .findSubmissionDatesByParticipantIdOrderBySubmissionDateDesc(participant.getParticipantId());
        
        if (submissionDates.isEmpty()) {
            return 0;
        }
        
        // Count consecutive days from today backwards
        int streak = 0;
        LocalDate currentDate = LocalDate.now();
        
        // Don't count future dates or dates after challenge end
        if (currentDate.isAfter(challenge.getEndDate())) {
            currentDate = challenge.getEndDate();
        }
        
        // Look for consecutive days with submissions
        for (int i = 0; i < submissionDates.size(); i++) {
            LocalDate submissionDate = submissionDates.get(i);
            
            // If this submission is for the current date we're checking
            if (submissionDate.equals(currentDate)) {
                streak++;
                // Move to previous day
                currentDate = currentDate.minusDays(1);
            } else if (submissionDate.isBefore(currentDate)) {
                // We have a gap in consecutive days
                break;
            }
            
            // Stop if we reach the challenge start date
            if (currentDate.isBefore(challenge.getStartDate())) {
                break;
            }
        }
        
        return streak;
    }
    
    /**
     * Calculates the longest streak for a participant (simplified)
     */
    private int calculateLongestStreak(ChallengeParticipant participant) {
        // This is a simplified implementation
        // In a real app, we would look at all consecutive daily submissions
        
        // Get all submission dates ordered by date
        List<LocalDate> submissionDates = submissionRepository
                .findSubmissionDatesByParticipantIdOrderBySubmissionDate(participant.getParticipantId());
        
        if (submissionDates.isEmpty()) {
            return 0;
        }
        
        int longestStreak = 1;
        int currentStreak = 1;
        
        // Find longest consecutive streak
        for (int i = 1; i < submissionDates.size(); i++) {
            // If this date is exactly one day after the previous date
            if (submissionDates.get(i).equals(submissionDates.get(i-1).plusDays(1))) {
                currentStreak++;
                longestStreak = Math.max(longestStreak, currentStreak);
            } else {
                // Reset streak counter
                currentStreak = 1;
            }
        }
        
        return longestStreak;
    }
}
