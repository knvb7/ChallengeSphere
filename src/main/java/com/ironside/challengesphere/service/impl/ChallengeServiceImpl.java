package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.api.response.UserProfileResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeParticipant;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.ChallengeParticipantRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.UserService;
import com.ironside.challengesphere.util.ChallengeStatus;
import com.ironside.challengesphere.util.ChallengeType;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChallengeServiceImpl implements ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final ChallengeParticipantRepository participantRepository;
    private final UserService userService;

    @Override
    @Transactional
    public ChallengeResponse createChallenge(Long creatorUserId, ChallengeRequest challengeRequest) {
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + creatorUserId));

        Challenge challenge = new Challenge();
        challenge.setCreator(creator);
        challenge.setTitle(challengeRequest.getTitle());
        challenge.setDescription(challengeRequest.getDescription());
        challenge.setRules(challengeRequest.getRules());
        challenge.setStartDate(challengeRequest.getStartDate());
        challenge.setEndDate(challengeRequest.getEndDate());
        challenge.setChallengeType(challengeRequest.getChallengeType());
        challenge.setStatus(ChallengeStatus.ACTIVE);
        
        // Generate a unique invite code
        String inviteCode = generateUniqueInviteCode();
        challenge.setInviteCode(inviteCode);

        // Save the challenge
        Challenge savedChallenge = challengeRepository.save(challenge);

        // Auto-join the creator to their challenge
        ChallengeParticipant creatorParticipant = new ChallengeParticipant();
        creatorParticipant.setChallenge(savedChallenge);
        creatorParticipant.setUser(creator);
        creatorParticipant.setIsAccepted(true);
        participantRepository.save(creatorParticipant);

        return mapToChallengeResponse(savedChallenge, creatorUserId);
    }

    @Override
    @Transactional
    public ChallengeResponse updateChallenge(Long challengeId, ChallengeRequest challengeRequest) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with id: " + challengeId));

        // Update fields
        challenge.setTitle(challengeRequest.getTitle());
        challenge.setDescription(challengeRequest.getDescription());
        challenge.setRules(challengeRequest.getRules());
        challenge.setStartDate(challengeRequest.getStartDate());
        challenge.setEndDate(challengeRequest.getEndDate());
        challenge.setChallengeType(challengeRequest.getChallengeType());

        // Save updated challenge
        Challenge updatedChallenge = challengeRepository.save(challenge);

        return mapToChallengeResponse(updatedChallenge, updatedChallenge.getCreator().getUserId());
    }

    @Override
    @Transactional
    public void deleteChallenge(Long challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with id: " + challengeId));

        // Set status to CANCELLED instead of deleting
        challenge.setStatus(ChallengeStatus.CANCELLED);
        challengeRepository.save(challenge);
    }

    @Override
    public ChallengeResponse getChallengeById(Long challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with id: " + challengeId));

        return mapToChallengeResponse(challenge, null);
    }

    @Override
    public ChallengeResponse getChallengeByInviteCode(String inviteCode) {
        Challenge challenge = challengeRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with invite code: " + inviteCode));

        return mapToChallengeResponse(challenge, null);
    }

    @Override
    public List<ChallengeResponse> findChallenges(ChallengeType type, ChallengeStatus status, 
                                                 LocalDate startDate, LocalDate endDate) {
        // This would be a more complex query in a real application
        // For now, we'll just use a simple approach with filters
        
        List<Challenge> challenges = challengeRepository.findAll();
        List<Challenge> filteredChallenges = challenges.stream()
                .filter(c -> type == null || c.getChallengeType() == type)
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> startDate == null || !c.getStartDate().isBefore(startDate))
                .filter(c -> endDate == null || !c.getEndDate().isAfter(endDate))
                .collect(Collectors.toList());

        return filteredChallenges.stream()
                .map(c -> mapToChallengeResponse(c, null))
                .collect(Collectors.toList());
    }

    @Override
    public List<ChallengeResponse> getCreatedChallenges(Long userId) {
        List<Challenge> challenges = challengeRepository.findByCreatorUserId(userId);
        
        return challenges.stream()
                .map(c -> mapToChallengeResponse(c, userId))
                .collect(Collectors.toList());
    }

    @Override
    public List<ChallengeResponse> getParticipatingChallenges(Long userId) {
        List<ChallengeParticipant> participations = participantRepository.findByUserIdAndIsAcceptedTrue(userId);
        
        return participations.stream()
                .map(p -> mapToChallengeResponse(p.getChallenge(), userId))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isUserChallengeCreator(Long userId, Long challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Challenge not found with id: " + challengeId));
        
        return challenge.getCreator().getUserId().equals(userId);
    }

    /**
     * Helper method to generate a unique invite code
     */
    private String generateUniqueInviteCode() {
        String inviteCode;
        boolean isUnique = false;
        
        do {
            // Generate a random alphanumeric code
            inviteCode = RandomStringUtils.randomAlphanumeric(8).toUpperCase();
            
            // Check if it's unique
            isUnique = !challengeRepository.findByInviteCode(inviteCode).isPresent();
        } while (!isUnique);
        
        return inviteCode;
    }

    /**
     * Helper method to map Challenge entity to ChallengeResponse
     */
    private ChallengeResponse mapToChallengeResponse(Challenge challenge, Long currentUserId) {
        ChallengeResponse response = new ChallengeResponse();
        
        response.setId(challenge.getChallengeId());
        response.setTitle(challenge.getTitle());
        response.setDescription(challenge.getDescription());
        response.setRules(challenge.getRules());
        response.setStartDate(challenge.getStartDate());
        response.setEndDate(challenge.getEndDate());
        response.setChallengeType(challenge.getChallengeType());
        response.setStatus(challenge.getStatus());
        response.setInviteCode(challenge.getInviteCode());
        response.setCreatedAt(challenge.getCreatedAt());
        
        // Get creator profile
        UserProfileResponse creatorProfile = userService.getUserProfile(challenge.getCreator().getUserId());
        response.setCreator(creatorProfile);
        
        // Count participants
        int participantCount = participantRepository.countByChallengeIdAndIsAcceptedTrue(challenge.getChallengeId());
        response.setParticipantCount(participantCount);
        
        // Check if current user is participating (if userId provided)
        if (currentUserId != null) {
            boolean isParticipating = participantRepository
                    .existsByUserIdAndChallengeIdAndIsAcceptedTrue(currentUserId, challenge.getChallengeId());
            response.setCurrentUserParticipating(isParticipating);
        } else {
            response.setCurrentUserParticipating(false);
        }
        
        return response;
    }
}
