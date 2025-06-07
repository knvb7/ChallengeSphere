package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.service.ChallengeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChallengeServiceImpl implements ChallengeService {
    private final ChallengeRepository challengeRepository;

    @Override
    public ChallengeResponse createChallenge(ChallengeCreateRequest request) {
        Challenge challenge = new Challenge();
        challenge.setTitle(request.getTitle());
        challenge.setDescription(request.getDescription());
        challenge.setCreatorUserId(request.getCreatorUserId());
        Challenge saved = challengeRepository.save(challenge);
        return toResponse(saved);
    }

    @Override
    public ChallengeResponse getChallenge(Long id) {
        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Challenge not found"));
        return toResponse(challenge);
    }

    @Override
    public List<ChallengeResponse> getAllChallenges() {
        return challengeRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ChallengeResponse> getAllChallengesByUserId(Long userId) {
        return challengeRepository.findByCreatorUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private ChallengeResponse toResponse(Challenge challenge) {
        ChallengeResponse response = new ChallengeResponse();
        BeanUtils.copyProperties(challenge, response);
        return response;
    }
}
