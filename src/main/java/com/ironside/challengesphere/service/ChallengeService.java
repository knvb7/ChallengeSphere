package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface ChallengeService {
    ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException;
    ChallengeResponse getChallenge(Long id);
    List<ChallengeResponse> getAllChallengesByUserId(Long userId);
    UserChallengesResponse getUserChallenges(GetUserChallengesRequest request) throws ChallengeSphereException;
    ChallengeResponse updateChallenge(Long id, ChallengeUpdateRequest request);
    ChallengeResponse updateChallengeStatus(Long id, boolean active);
    void deleteChallenge(Long id);
    void disableOrSoftDeleteChallengeForUser(Long challengeId, Long userId, boolean disable, boolean softDelete);
    List<Object[]> getMediaUserChallengeForChallenge(List<Long> challengeIds);
    List<Object[]> getMediaUserChallengeForUser(Long userId);
    void markChallengeAsCompleted(Long challengeId);
}
