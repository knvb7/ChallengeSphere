package com.ironside.challengesphere.service;

import java.time.LocalDate;
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
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.ChallengeDetailResponse;
import com.ironside.challengesphere.api.response.ChallengeFavoriteResponse;
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
    UserChallengesResponse getPublicChallengesByCreator(String creatorId, String currentUserId, int limit, String cursor) throws ChallengeSphereException;
    ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException;
    Challenge updateChallenge(String id, ChallengeUpdateRequest request, String actorUserId, boolean admin) throws ChallengeSphereException;
    void inviteChallengeUsers(String challengeId, String actorUserId, List<String> invitedUserIds) throws ChallengeSphereException;
    Optional<Challenge> getChallengeById(String challengeId) throws ChallengeSphereException;
    List<Challenge> getChallengeByIds(List<String> challengeId, String currentUserId) throws ChallengeSphereException;
    void deleteChallenge(String challengeId, String actorUserId) throws ChallengeSphereException;
    void deleteChallenges(List<String> challengeIds);
    void disableChallengesForUsers(List<String> userIds) throws ChallengeSphereException;
    JoinChallengeResponse joinChallenge(JoinChallengeRequest request) throws ChallengeSphereException;
    JoinChallengeResponse joinChallengeFromVerifiedInvite(JoinChallengeRequest request) throws ChallengeSphereException;
    LeaveChallengeResponse leaveChallenge(LeaveChallengeRequest request) throws ChallengeSphereException;
    LeaveChallengeResponse removeParticipant(String challengeId, String actorUserId, String participantUserId, String reason) throws ChallengeSphereException;
    ChallengeParticipantsResponse getChallengeParticipants(GetChallengeParticipantsRequest request) throws ChallengeSphereException;
    ChallengesByHashtagResponse getChallengesByHashtag(GetChallengesByHashtagRequest request) throws ChallengeSphereException;
    PopularChallengesResponse getPopularChallenges(GetPopularChallengesRequest request) throws ChallengeSphereException;
    RecentChallengesResponse getRecentChallenges(String currentUserId, int limit, String cursor) throws ChallengeSphereException;
    CursorPageResponse<String> getTrendingChallengeIds(int limit, String cursor);
    ChallengeDetailResponse getChallengeDetail(String challengeId, String currentUserId) throws ChallengeSphereException;
    ChallengeProgressResponse getChallengeProgress(String challengeId, String userId) throws ChallengeSphereException;
    ChallengeProgressResponse getChallengeProgress(String challengeId, String userId, LocalDate currentDate) throws ChallengeSphereException;
    ChallengeFavoriteResponse setChallengeFavorite(String challengeId, String userId, boolean favorite) throws ChallengeSphereException;
    MyChallengesResponse getMyChallenges(String userId, String currentUserId, String status, int limit, String cursor) throws ChallengeSphereException;
    MyChallengesResponse getMyChallenges(String userId, String currentUserId, String status, int limit, String cursor, LocalDate currentDate) throws ChallengeSphereException;
    PresignedUrlsResponse generateChallengePresignedUrls(GeneratePresignedUrlsRequest request) throws ChallengeSphereException;
}
