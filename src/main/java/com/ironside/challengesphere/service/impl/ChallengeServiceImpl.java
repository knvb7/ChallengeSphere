package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.ChallengeCreateRequest;
import com.ironside.challengesphere.api.request.ChallengeUpdateRequest;
import com.ironside.challengesphere.api.request.GetUserChallengesRequest;
import com.ironside.challengesphere.api.response.ChallengeCreateResponse;
import com.ironside.challengesphere.api.response.ChallengeResponse;
import com.ironside.challengesphere.api.response.UserChallengesResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.ChallengeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChallengeServiceImpl implements ChallengeService {
    private static final Logger logger = LogManager.getLogger(ChallengeServiceImpl.class);

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;


    @Override
    public UserChallengesResponse getUserChallenges(GetUserChallengesRequest request) throws ChallengeSphereException {
        Long userId = request.getUserId();
        int page = request.getPage();
        int pageSize = request.getPageSize();
        Boolean activeFilter = request.getActive();

        logger.info("Getting challenges for userId: {}, page: {}, pageSize: {}, activeFilter: {}",
                   userId, page, pageSize, activeFilter);

        try {
            if (!userRepository.existsById(userId)) {
                throw new ChallengeSphereException(
                        "User not found with id: " + userId,
                        HttpStatus.NOT_FOUND,
                        logger
                );
            }

            List<Challenge> createdChallenges = challengeRepository.findByCreatorId(userId);
            List<Challenge> ownedChallenges = challengeRepository.findByCurrentOwnerId(userId);

            Set<Challenge> allUserChallengesSet = new HashSet<>(createdChallenges);
            allUserChallengesSet.addAll(ownedChallenges);
            List<Challenge> allUserChallenges = new ArrayList<>(allUserChallengesSet);

            if (activeFilter != null) {
                allUserChallenges = allUserChallenges.stream()
                        .filter(challenge -> challenge.getActive().equals(activeFilter))
                        .collect(Collectors.toList());
            }

            int totalCount = allUserChallenges.size();
            int offset = (page - 1) * pageSize;
            List<Challenge> paginatedChallenges = allUserChallenges.stream()
                    .skip(offset)
                    .limit(pageSize)
                    .collect(Collectors.toList());

            logger.info("Found {} challenges for userId: {} (page {}/{})",
                       paginatedChallenges.size(), userId, page, (int) Math.ceil((double) totalCount / pageSize));

            return UserChallengesResponse.create(userId, paginatedChallenges, page, pageSize, totalCount);

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting challenges for userId: {}: {}", userId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get user challenges",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    @Override
    public ChallengeCreateResponse createChallenge(ChallengeCreateRequest request) throws ChallengeSphereException {
        logger.info("Creating challenge with title: {} for creator userId: {}", request.getTitle(), request.getCreatorUserId());

        try {
            // Validate that the creator user exists
            if (!userRepository.existsById(request.getCreatorUserId())) {
                throw new ChallengeSphereException(
                        "Creator user not found with id: " + request.getCreatorUserId(),
                        HttpStatus.NOT_FOUND,
                        logger
                );
            }

            // Create new challenge entity
            Challenge challenge = new Challenge();
            challenge.setTitle(request.getTitle());
            challenge.setDescription(request.getDescription());
            challenge.setHashtags(request.getHashtags() != null ? request.getHashtags() : "");
            challenge.setCategories(request.getCategories() != null ? request.getCategories() : "");
            challenge.setBenefits(request.getBenefits() != null ? request.getBenefits() : "");
            challenge.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);
            challenge.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
            challenge.setCreatorId(request.getCreatorUserId());
            challenge.setCurrentOwnerId(request.getCreatorUserId());
            challenge.setActive(true);
            challenge.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            challenge.setNumberOfDays(request.getNumberOfDays());

            // Save challenge to database
            Challenge savedChallenge = challengeRepository.save(challenge);
            logger.info("Successfully created challenge with id: {} for user: {}", savedChallenge.getId(), request.getCreatorUserId());

            // Return success response
            return ChallengeCreateResponse.success(
                    savedChallenge.getId(),
                    savedChallenge.getTitle(),
                    savedChallenge.getDescription(),
                    savedChallenge.getHashtags(),
                    savedChallenge.getCategories(),
                    savedChallenge.getBenefits(),
                    savedChallenge.getIsPrivate(),
                    savedChallenge.getStartDate(),
                    savedChallenge.getActive(),
                    savedChallenge.getCreatedAt(),
                    savedChallenge.getCreatorId(),
                    savedChallenge.getCurrentOwnerId(),
                    savedChallenge.getNumberOfDays()
            );

        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error creating challenge for creator userId: {}: {}", request.getCreatorUserId(), e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to create challenge",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }

    // Placeholder implementations for other methods
    @Override
    public ChallengeResponse getChallenge(Long id) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<ChallengeResponse> getAllChallengesByUserId(Long userId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public ChallengeResponse updateChallenge(Long id, ChallengeUpdateRequest request) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public ChallengeResponse updateChallengeStatus(Long id, boolean active) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void deleteChallenge(Long id) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void disableOrSoftDeleteChallengeForUser(Long challengeId, Long userId, boolean disable, boolean softDelete) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<Object[]> getMediaUserChallengeForChallenge(List<Long> challengeIds) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<Object[]> getMediaUserChallengeForUser(Long userId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void markChallengeAsCompleted(Long challengeId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
