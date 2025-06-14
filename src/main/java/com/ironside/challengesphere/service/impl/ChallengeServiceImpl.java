package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.service.ChallengeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChallengeServiceImpl implements ChallengeService {
    @Autowired
    private ChallengeRepository challengeRepository;

    private static final Logger logger = LogManager.getLogger(ChallengeServiceImpl.class);

    @Override
    public ChallengeResponse createChallenge(ChallengeCreateRequest request) {
        logger.info("Creating challenge for userId={}, title={}", request.getCreatorUserId(), request.getTitle());
        Challenge challenge = new Challenge();
        challenge.setTitle(request.getTitle());
        challenge.setDescription(request.getDescription());
        challenge.setCreatorUserId(request.getCreatorUserId());
        challenge.setEndDate(request.getEndDate());
        challenge.setNumberOfDays(request.getNumberOfDays());
        challenge.setReminderTime(request.getReminderTime());
        challenge.setActive(true);
        Challenge saved = challengeRepository.save(challenge);
        String shareCode = generateShareCode(saved.getId(), saved.getCreatorUserId());
        saved.setShareCode(shareCode);
        challengeRepository.save(saved);
        logger.info("Challenge created with id={}, shareCode={}", saved.getId(), shareCode);
        return toResponse(saved);
    }

    @Override
    public ChallengeResponse getChallenge(Long id) {
        logger.debug("Fetching challenge with id={}", id);
        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Challenge not found"));
        return toResponse(challenge);
    }

    @Override
    public List<ChallengeResponse> getAllChallengesByUserId(Long userId) {
        logger.debug("Fetching all challenges for userId={}", userId);
        return challengeRepository.findByCreatorUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ChallengeResponse updateChallenge(Long id, ChallengeUpdateRequest request) {
        logger.info("Updating challenge with id={}", id);
        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Challenge not found"));
        if (request.getTitle() != null) challenge.setTitle(request.getTitle());
        if (request.getDescription() != null) challenge.setDescription(request.getDescription());
        if (request.getEndDate() != null) challenge.setEndDate(request.getEndDate());
        if (request.getNumberOfDays() != null) challenge.setNumberOfDays(request.getNumberOfDays());
        if (request.getReminderTime() != null) challenge.setReminderTime(request.getReminderTime());
        if (request.getActive() != null) challenge.setActive(request.getActive());
        challenge.setUpdatedAt(Timestamp.from(Instant.now()));
        Challenge saved = challengeRepository.save(challenge);
        logger.info("Challenge updated: id={}", id);
        return toResponse(saved);
    }

    @Override
    public ChallengeResponse updateChallengeStatus(Long id, boolean active) {
        logger.info("Updating status for challenge id={}, active={}", id, active);
        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Challenge not found"));
        challenge.setActive(active);
        challenge.setUpdatedAt(Timestamp.from(Instant.now()));
        Challenge saved = challengeRepository.save(challenge);
        logger.info("Challenge status updated: id={}, active={}", id, active);
        return toResponse(saved);
    }

    @Override
    public void deleteChallenge(Long id) {
        logger.info("Making challenge with id={} as inactive.", id);
        Challenge challenge = challengeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Challenge not found"));
        challenge.setActive(false);
        challengeRepository.save(challenge);
        logger.info("Challenge is made inactive: id={}", id);
    }

    private ChallengeResponse toResponse(Challenge challenge) {
        ChallengeResponse response = new ChallengeResponse();
        BeanUtils.copyProperties(challenge, response);
        return response;
    }

    private String generateShareCode(Long challengeId, Long creatorUserId) {
        try {
            String input = challengeId + ":" + creatorUserId;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 10);
        } catch (Exception e) {
            throw new RuntimeException("Error generating share code", e);
        }
    }
}
