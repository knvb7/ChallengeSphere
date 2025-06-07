package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import java.util.List;

public interface ChallengeService {
    ChallengeResponse createChallenge(ChallengeCreateRequest request);
    ChallengeResponse getChallenge(Long id);
    List<ChallengeResponse> getAllChallenges();
    List<ChallengeResponse> getAllChallengesByUserId(Long userId);
}
