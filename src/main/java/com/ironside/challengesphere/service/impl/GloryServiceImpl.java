package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.GloryUserResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.GloryService;
import com.ironside.challengesphere.service.NotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class GloryServiceImpl implements GloryService {

    private static final Logger logger = LogManager.getLogger(GloryServiceImpl.class);
    private static final int MAX_LIMIT = 100;

    @Autowired
    private GloryRepository gloryRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    private GloryUserResponse toGloryUserResponse(Glory glory, User user) {
        GloryUserResponse response = GloryUserResponse.from(glory, user);
        if (response.getProfilePicUrl() != null && !response.getProfilePicUrl().isBlank()) {
            response.setProfilePicUrl(
                    mediaUrlService.toReadableProfilePhoto(user.getId(), response.getProfilePicUrl()));
        }
        return response;
    }

    @Override
    @Transactional
    public boolean toggleGlory(String submissionId, String userId) throws ChallengeSphereException {
        logger.info("Toggle glory for submission: {} by user: {}", submissionId, userId);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));

        if (userId.equals(submission.getUserId()) || interactionForbidden(submission, userId)) {
            throw new ChallengeSphereException(
                    "Not authorized to glory this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        GloryRepository.GloryTransition transition = gloryRepository.toggleGlory(submissionId, userId);
        submission = transition.submission();
        boolean isGiven = transition.active();

        if (transition.activeDelta() != 0) {
            logger.info("Atomically updated glory, submission count, and leaderboard: {} ({})", submissionId,
                    transition.activeDelta() > 0 ? "incremented" : "decremented");
        }

        invalidateInteractionCaches(submission);
        if (isGiven) {
            notifyGloryAfterCommit(submission, userId);
        }

        return isGiven;
    }

    private void notifyGloryAfterCommit(Submission submission, String actorUserId) {
        Runnable notify = () -> {
            try {
                notificationService.notifyGloryAsync(submission, actorUserId);
            } catch (Exception exception) {
                logger.warn("Failed to schedule glory notification for submissionId={}, actorUserId={}: {}",
                        submission == null ? null : submission.getId(),
                        actorUserId,
                        exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
            return;
        }
        notify.run();
    }


    @Override
    public List<Glory> getGloriesForSubmission(String submissionId) {
        logger.info("Getting glories for submission: {}", submissionId);
        return gloryRepository.findActiveBySubmissionId(submissionId);
    }

    @Override
    public CursorPageResponse<GloryUserResponse> getGloriesForSubmission(
            String submissionId,
            String callerId,
            int limit,
            String cursor
    ) throws ChallengeSphereException {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String queryBinding = "glories:v1|submission=" + submissionId
                + "|viewer=" + callerId
                + "|limit=" + boundedLimit;
        logger.info("Getting glories for submission: {}, limit: {}", submissionId, boundedLimit);
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        if (interactionForbidden(submission, callerId)) {
            throw new ChallengeSphereException("Not authorized to view glories for this submission",
                    HttpStatus.FORBIDDEN, logger);
        }
        Map<String, AttributeValue> continuation = decodeCursor(cursor, queryBinding);
        java.util.Set<String> blockedUserIds = java.util.Set.copyOf(
                userBlockRepository.findActiveBlockedCounterpartIds(callerId)
        );
        List<Glory> glories = new ArrayList<>(boundedLimit);
        for (int pageNumber = 0; pageNumber < 10 && glories.size() < boundedLimit; pageNumber++) {
            DynamoPage<Glory> page = gloryRepository.findActivePageBySubmissionId(
                    submissionId,
                    Math.max(1, boundedLimit - glories.size()),
                    continuation
            );
            page.items().stream()
                    .filter(glory -> !blockedUserIds.contains(glory.getUserId()))
                    .forEach(glories::add);
            continuation = page.lastEvaluatedKey();
            if (continuation.isEmpty()) break;
        }
        List<String> userIds = glories.stream()
                .map(Glory::getUserId)
                .distinct()
                .toList();
        Map<String, User> usersById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        List<GloryUserResponse> responses = glories.stream()
                .filter(glory -> usersById.containsKey(glory.getUserId()))
                .map(glory -> toGloryUserResponse(glory, usersById.get(glory.getUserId())))
                .toList();
        boolean hasMore = !continuation.isEmpty();
        return CursorPageResponse.of(
                responses,
                encodeCursor(queryBinding, continuation),
                hasMore
        );
    }

    private Map<String, AttributeValue> decodeCursor(String cursor, String queryBinding)
            throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            return cursorCodec.decode(cursor, queryBinding).lastEvaluatedKey();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(String queryBinding, Map<String, AttributeValue> continuation) {
        if (continuation == null || continuation.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(continuation)
        );
    }

    @Override
    public boolean hasUserGivenGlory(String submissionId, String userId) throws ChallengeSphereException {
        logger.info("Checking if user {} has given glory to submission {}", userId, submissionId);
        requireSubmissionAccess(submissionId, userId, "view glory status for");
        return gloryRepository.existsBySubmissionIdAndUserId(submissionId, userId);
    }

    @Override
    @Transactional
    public boolean toggleUnglory(String submissionId, String userId) throws ChallengeSphereException {
        logger.info("Toggle unglory for submission: {} by user: {}", submissionId, userId);

        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId, HttpStatus.NOT_FOUND, logger));
        if (submission.getUserId() != null && submission.getUserId().equals(userId)) {
            throw new ChallengeSphereException(
                    "Cannot unglory your own submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (interactionForbidden(submission, userId)) {
            throw new ChallengeSphereException(
                    "Not authorized to unglory this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }

        GloryRepository.GloryTransition transition = gloryRepository.toggleUnglory(submissionId, userId);
        submission = transition.submission();
        boolean isUngloried = transition.ungloried();

        logger.info("Toggled unglory={} for submission: {} by user: {}", isUngloried, submissionId, userId);
        invalidateInteractionCaches(submission);
        return isUngloried;
    }

    @Override
    public boolean hasUserUngloried(String submissionId, String userId) throws ChallengeSphereException {
        logger.info("Checking if user {} has ungloried submission {}", userId, submissionId);
        requireSubmissionAccess(submissionId, userId, "view glory status for");
        return gloryRepository.existsUngloriedBySubmissionIdAndUserId(submissionId, userId);
    }

    @Override
    public Long getGloryCount(String submissionId, String viewerUserId) throws ChallengeSphereException {
        logger.info("Getting glory count for submission: {}", submissionId);
        Submission submission = requireSubmissionAccess(submissionId, viewerUserId, "view glory count for");
        return submission.getGloriesCount() == null ? 0L : submission.getGloriesCount();
    }

    private Submission requireSubmissionAccess(String submissionId, String viewerUserId, String action)
            throws ChallengeSphereException {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Submission not found with id: " + submissionId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        if (interactionForbidden(submission, viewerUserId)) {
            throw new ChallengeSphereException(
                    "Not authorized to " + action + " this submission",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        return submission;
    }

    private void invalidateInteractionCaches(Submission submission) {
        if (submission == null) {
            return;
        }
        try {
            cacheService.invalidateGloryCaches(submission.getId());
            cacheService.invalidateAllFeeds();
        } catch (Exception e) {
            logger.warn("Failed to invalidate interaction caches for submission {}: {}", submission.getId(), e.getClass().getSimpleName());
        }
    }

    private boolean interactionForbidden(Submission submission, String viewerId) {
        if (submission == null || viewerId == null) {
            return true;
        }
        if (viewerId.equals(submission.getUserId())) {
            return false;
        }
        if (userBlockRepository.existsActiveBlockBetween(viewerId, submission.getUserId())) {
            return true;
        }
        if (!Boolean.TRUE.equals(submission.getActive())) {
            return true;
        }

        Challenge challenge = challengeRepository.findById(submission.getChallengeId()).orElse(null);
        if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) {
            return true;
        }

        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) && !Boolean.TRUE.equals(submission.getIsPrivate())) {
            return false;
        }

        return userChallengeRepository.findByUserIdAndChallengeId(viewerId, submission.getChallengeId())
                .map(userChallenge -> !Boolean.TRUE.equals(userChallenge.getActive())
                        || Boolean.TRUE.equals(userChallenge.getBanned()))
                .orElse(true);
    }
}
