package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ChallengeRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.util.ChallengeStatus;
import com.ironside.challengesphere.util.ChallengeType;

import java.time.LocalDate;
import java.util.List;

public interface ChallengeService {
    
    /**
     * Creates a new challenge
     * 
     * @param creatorUserId user ID of the challenge creator
     * @param challengeRequest challenge details
     * @return the created challenge
     */
    ChallengeResponse createChallenge(Long creatorUserId, ChallengeRequest challengeRequest);
    
    /**
     * Updates an existing challenge
     * 
     * @param challengeId ID of the challenge to update
     * @param challengeRequest challenge details
     * @return the updated challenge
     */
    ChallengeResponse updateChallenge(Long challengeId, ChallengeRequest challengeRequest);
    
    /**
     * Deletes a challenge
     * 
     * @param challengeId ID of the challenge to delete
     */
    void deleteChallenge(Long challengeId);
    
    /**
     * Gets a challenge by ID
     * 
     * @param challengeId ID of the challenge to retrieve
     * @return the requested challenge
     */
    ChallengeResponse getChallengeById(Long challengeId);
    
    /**
     * Gets a challenge by invite code
     * 
     * @param inviteCode invite code of the challenge
     * @return the requested challenge
     */
    ChallengeResponse getChallengeByInviteCode(String inviteCode);
    
    /**
     * Finds challenges matching the given criteria
     * 
     * @param type challenge type to filter by (optional)
     * @param status challenge status to filter by (optional)
     * @param startDate minimum start date (optional)
     * @param endDate maximum end date (optional)
     * @return list of challenges matching the criteria
     */
    List<ChallengeResponse> findChallenges(ChallengeType type, ChallengeStatus status, 
                                          LocalDate startDate, LocalDate endDate);
    
    /**
     * Gets all challenges created by a user
     * 
     * @param userId ID of the user
     * @return list of challenges created by the user
     */
    List<ChallengeResponse> getCreatedChallenges(Long userId);
    
    /**
     * Gets all challenges a user is participating in
     * 
     * @param userId ID of the user
     * @return list of challenges the user is participating in
     */
    List<ChallengeResponse> getParticipatingChallenges(Long userId);
    
    /**
     * Checks if a user is the creator of a challenge
     * 
     * @param userId ID of the user
     * @param challengeId ID of the challenge
     * @return true if the user is the creator of the challenge
     */
    boolean isUserChallengeCreator(Long userId, Long challengeId);
}
