package com.ironside.challengesphere.service;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface ChallengeService {
    ChallengeResponse createChallenge(ChallengeCreateRequest request);
    ChallengeResponse getChallenge(Long id);
    List<ChallengeResponse> getAllChallengesByUserId(Long userId);
    ChallengeResponse updateChallenge(Long id, ChallengeUpdateRequest request);
    ChallengeResponse updateChallengeStatus(Long id, boolean active);
    void deleteChallenge(Long id);
}
