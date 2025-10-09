//package com.ironside.challengesphere.service;
//
//import java.util.List;
//
//public interface ParticipationService {
//
//    /**
//     * Joins a user to a challenge
//     *
//     * @param userId ID of the user joining
//     * @param challengeId ID of the challenge to join
//     */
//    void joinChallenge(Long userId, Long challengeId);
//
//    /**
//     * Joins a user to a challenge using an invite code
//     *
//     * @param userId ID of the user joining
//     * @param inviteCode invite code of the challenge to join
//     */
//    void joinChallengeByInviteCode(Long userId, String inviteCode);
//
//    /**
//     * Removes a user from a challenge
//     *
//     * @param userId ID of the user leaving
//     * @param challengeId ID of the challenge to leave
//     */
//    void leaveChallenge(Long userId, Long challengeId);
//
//    /**
//     * Gets all participants of a challenge
//     *
//     * @param challengeId ID of the challenge
//     * @return list of participants
//     */
//    List<ParticipantResponse> getChallengeParticipants(Long challengeId);
//
//    /**
//     * Gets a user's progress in a challenge
//     *
//     * @param userId ID of the user
//     * @param challengeId ID of the challenge
//     * @return progress percentage (0-100)
//     */
//    Double getUserProgress(Long userId, Long challengeId);
//
//    /**
//     * Gets a leaderboard of participants for a challenge
//     *
//     * @param challengeId ID of the challenge
//     * @return list of participants sorted by completion metrics
//     */
//    List<ParticipantResponse> getChallengeLeaderboard(Long challengeId);
//}
