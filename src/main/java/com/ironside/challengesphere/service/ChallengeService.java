package com.ironside.challengesphere.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GetChallengeParticipantsRequest;
import com.ironside.challengesphere.api.request.GetChallengesByHashtagRequest;
import com.ironside.challengesphere.api.request.GetPopularChallengesRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeDetailResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengeProgressResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.MyChallengesResponse;
import com.ironside.challengesphere.api.response.PopularChallengesResponse;
import com.ironside.challengesphere.api.response.PresignedUrlsResponse;
import com.ironside.challengesphere.api.response.RecentChallengesResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;

@Service
public interface ChallengeService {
    UserChallengesResponse getUserChallenges(GetUserChallengesRequest request) throws ChallengeSphereException;
    ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException;
    Challenge updateChallenge(Long id, ChallengeUpdateRequest request, Long actorUserId, boolean admin) throws ChallengeSphereException;
    Optional<Challenge> getChallengeById(Long challengeId) throws ChallengeSphereException;
    List<Challenge> getChallengeByIds(List<Long> challengeId, Long currentUserId) throws ChallengeSphereException;
    void deleteChallenges(List<Long> challengeIds);
    void disableChallengesForUsers(List<Long> userIds) throws ChallengeSphereException;
    JoinChallengeResponse joinChallenge(JoinChallengeRequest request) throws ChallengeSphereException;
    LeaveChallengeResponse leaveChallenge(LeaveChallengeRequest request) throws ChallengeSphereException;
    ChallengeParticipantsResponse getChallengeParticipants(GetChallengeParticipantsRequest request) throws ChallengeSphereException;
    ChallengesByHashtagResponse getChallengesByHashtag(GetChallengesByHashtagRequest request) throws ChallengeSphereException;
    PopularChallengesResponse getPopularChallenges(GetPopularChallengesRequest request) throws ChallengeSphereException;
    
    /**
     * Get recent challenges with caching
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @return Recent challenges response
     */
    RecentChallengesResponse getRecentChallenges(int page, int pageSize, Long currentUserId) throws ChallengeSphereException;
    
    /**
     * Get trending challenges based on engagement
     * @param limit Number of challenges to return
     * @return List of trending challenge IDs
     */
    List<Long> getTrendingChallengeIds(int limit);

    ChallengeDetailResponse getChallengeDetail(Long challengeId, Long currentUserId) throws ChallengeSphereException;

    ChallengeProgressResponse getChallengeProgress(Long challengeId, Long userId) throws ChallengeSphereException;

    MyChallengesResponse getMyChallenges(Long userId, Long currentUserId, String status, int page, int pageSize) throws ChallengeSphereException;

    PresignedUrlsResponse generateChallengePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException;
}
