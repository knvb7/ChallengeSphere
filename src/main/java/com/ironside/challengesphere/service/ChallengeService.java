package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.LeaveChallengeRequest;
import com.ironside.challengesphere.api.request.GetChallengeParticipantsRequest;
import com.ironside.challengesphere.api.request.GetChallengesByHashtagRequest;
import com.ironside.challengesphere.api.request.GetPopularChallengesRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.api.response.ChallengeParticipantsResponse;
import com.ironside.challengesphere.api.response.ChallengesByHashtagResponse;
import com.ironside.challengesphere.api.response.PopularChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface ChallengeService {
    UserChallengesResponse getUserChallenges(GetUserChallengesRequest request) throws ChallengeSphereException;
    ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException;
    List<Challenge> getChallengeByIds(List<Long> challengeId) throws ChallengeSphereException;
    void deleteChallenges(List<Long> challengeIds);
    void disableChallengesForUsers(List<Long> userIds) throws ChallengeSphereException;
    JoinChallengeResponse joinChallenge(JoinChallengeRequest request) throws ChallengeSphereException;
    LeaveChallengeResponse leaveChallenge(LeaveChallengeRequest request) throws ChallengeSphereException;
    ChallengeParticipantsResponse getChallengeParticipants(GetChallengeParticipantsRequest request) throws ChallengeSphereException;
    ChallengesByHashtagResponse getChallengesByHashtag(GetChallengesByHashtagRequest request) throws ChallengeSphereException;
    PopularChallengesResponse getPopularChallenges(GetPopularChallengesRequest request) throws ChallengeSphereException;
}