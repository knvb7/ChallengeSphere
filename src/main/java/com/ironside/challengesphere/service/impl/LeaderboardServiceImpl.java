package com.ironside.challengesphere.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ironside.challengesphere.api.request.GetLeaderboardRequest;
import com.ironside.challengesphere.api.request.UpdateLeaderboardScoreRequest;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.api.response.LeaderboardResponse.LeaderboardEntryDTO;
import com.ironside.challengesphere.api.response.LeaderboardResponse.UserRankDTO;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.LeaderboardEntry;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeParticipantCountStore;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.LeaderboardEntryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.util.StreakCalculator;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Leaderboard Service Implementation
 * 
 * HYBRID APPROACH:
 * - Database: Source of truth for leaderboard data (durable)
 * - Redis: Cache layer for fast reads (sorted sets for O(log N) ranking)
 * 
 * Flow:
 * - Reads: Redis first → fallback to DB → populate Redis
 * - Writes: DB first → sync to Redis
 */
@Service
public class LeaderboardServiceImpl implements LeaderboardService {
    
    private static final Logger logger = LogManager.getLogger(LeaderboardServiceImpl.class);
    private static final String CURSOR_PAGE_STATE = "page";
    private static final String CURSOR_NEXT_RANK = "nextRank";
    
    // Scoring weights for different activities
    private static final double SUBMISSION_POINTS = 10.0;
    private static final double ADDITIONAL_MEDIA_POINTS = 1.0;
    
    @Autowired
    private ChallengeRepository challengeRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserChallengeRepository userChallengeRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;
    
    @Autowired
    private SubmissionRepository submissionRepository;
    
    @Autowired
    private LeaderboardEntryRepository leaderboardEntryRepository;

    @Autowired
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    @Autowired
    private ChallengeParticipantCountStore participantCountStore;

    @Override
    @Transactional(readOnly = true)
    public LeaderboardResponse getLeaderboard(GetLeaderboardRequest request) throws ChallengeSphereException {
        String challengeId = request.getChallengeId();
        int limit = request.getLimit();
        String userId = request.getUserId();
        String viewerUserId = request.getViewerUserId();
        String leaderboardType = request.getType() == null ? "OVERALL" : request.getType().name();
        String queryBinding = "leaderboard:v2|challenge=" + challengeId
                + "|viewer=" + cursorValue(viewerUserId)
                + "|type=" + leaderboardType
                + "|rankUser=" + cursorValue(userId)
                + "|limit=" + limit;
        
        logger.info("Getting leaderboard for challenge: {}, limit: {}, type: {}", 
            challengeId, limit, leaderboardType);
        
        try {
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            requireLeaderboardAccess(challenge, viewerUserId);
            LeaderboardCursor cursor = decodeCursor(request.getCursor(), queryBinding);
            DynamoPage<LeaderboardEntry> page = leaderboardEntryRepository.findActivePageForViewer(
                    challengeId,
                    viewerUserId,
                    limit,
                    cursor.lastEvaluatedKey()
            );
            boolean hasMore = page.hasMore();
            List<LeaderboardEntry> dbEntries = page.items();
            long activeParticipantCount = participantCountStore.getActiveCount(challengeId);
            Integer responseParticipantCount = activeParticipantCount > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : Math.toIntExact(activeParticipantCount);

            List<LeaderboardEntryDTO> entries = new ArrayList<>();
            for (int i = 0; i < dbEntries.size(); i++) {
                LeaderboardEntry le = dbEntries.get(i);
                User user = le.getUser();
                entries.add(LeaderboardEntryDTO.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getUsername())
                        .profilePictureUrl(readableProfilePic(user.getId(), user.getProfilePicUrl()))
                        .rank(cursor.nextRank() + i)
                        .score(le.getScore())
                        .submissionCount(le.getSubmissionCount())
                        .gloriesCount((long) le.getGloryPoints())
                        .currentStreak(le.getStreakDays())
                        .build());
                cacheService.addToLeaderboard(challengeId, user.getId(), le.getScore());
            }
            String nextCursor = encodeCursor(
                    queryBinding,
                    page.lastEvaluatedKey(),
                    cursor.nextRank() + entries.size()
            );
            LeaderboardResponse response;
            if (userId != null) {
                UserRankDTO userRank = getUserRankInternal(
                        challengeId,
                        userId,
                        viewerUserId
                );
                response = LeaderboardResponse.withUserRank(
                        challengeId,
                        challenge.getTitle(),
                        leaderboardType,
                        entries,
                        responseParticipantCount,
                        userRank,
                        nextCursor,
                        hasMore
                );
            } else {
                response = LeaderboardResponse.success(
                        challengeId,
                        challenge.getTitle(),
                        leaderboardType,
                        entries,
                        responseParticipantCount,
                        nextCursor,
                        hasMore
                );
            }
            
            logger.info("Retrieved leaderboard for challenge {} with {} entries", challengeId, entries.size());
            return response;
            
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting leaderboard for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to get leaderboard",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public UserRankDTO getUserRank(String challengeId, String userId, String viewerUserId)
            throws ChallengeSphereException {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "Challenge not found with id: " + challengeId,
                        HttpStatus.NOT_FOUND,
                        logger
                ));
        requireLeaderboardAccess(challenge, viewerUserId);
        return getUserRankInternal(challengeId, userId, viewerUserId);
    }

    private UserRankDTO getUserRankInternal(String challengeId, String userId, String viewerUserId)
            throws ChallengeSphereException {
        logger.debug("Getting user rank for challenge: {}, user: {}", challengeId, userId);

        try {
            if (!userId.equals(viewerUserId)
                    && userBlockRepository.existsActiveBlockBetween(viewerUserId, userId)) {
                return null;
            }
            Optional<LeaderboardEntry> entryOpt =
                    leaderboardEntryRepository.findByChallengeIdAndUserId(challengeId, userId);

            if (entryOpt.isEmpty()) {
                logger.debug("User {} not found in leaderboard for challenge {}", userId, challengeId);
                return null;
            }

            LeaderboardEntry entry = entryOpt.get();
            if (entry.getStatus() != LeaderboardEntry.LeaderboardStatus.ACTIVE) {
                return null;
            }
            LeaderboardEntryRepository.ViewerRankStats rankStats =
                    leaderboardEntryRepository.getViewerRankStats(
                    challengeId,
                    userId,
                    viewerUserId
            );
            Long rank = rankStats.rank();
            long totalParticipants = participantCountStore.getActiveCount(challengeId);
            cacheService.addToLeaderboard(challengeId, userId, entry.getScore());

            Double percentile = rank == null
                    ? null
                    : totalParticipants > 1
                    ? Math.round(
                            ((double) (totalParticipants - rank)
                                    / (totalParticipants - 1)) * 10_000.0
                    ) / 100.0
                    : 100.0;

            return UserRankDTO.builder()
                    .userId(userId)
                    .rank(rank)
                    .score(entry.getScore())
                    .totalParticipants(totalParticipants)
                    .percentile(percentile)
                    .build();

        } catch (Exception e) {
            logger.error("Error getting user rank for challenge {}, user {}: {}",
                challengeId, userId, e.getClass().getSimpleName());
            return null;
        }
    }

    private void requireLeaderboardAccess(Challenge challenge, String viewerUserId)
            throws ChallengeSphereException {
        if (viewerUserId == null) {
            throw new ChallengeSphereException(
                    "Authenticated user required",
                    HttpStatus.UNAUTHORIZED,
                    logger
            );
        }
        if (!Boolean.TRUE.equals(challenge.getActive())) {
            throw new ChallengeSphereException(
                    "Leaderboard is unavailable for an inactive challenge",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        String ownerUserId = challenge.getCurrentOwnerId() != null
                ? challenge.getCurrentOwnerId()
                : challenge.getCreatorId();
        if (!viewerUserId.equals(challenge.getCreatorId())
                && !viewerUserId.equals(ownerUserId)
                && ((challenge.getCreatorId() != null
                && userBlockRepository.existsActiveBlockBetween(viewerUserId, challenge.getCreatorId()))
                || (ownerUserId != null
                && !ownerUserId.equals(challenge.getCreatorId())
                && userBlockRepository.existsActiveBlockBetween(viewerUserId, ownerUserId)))) {
            throw new ChallengeSphereException(
                    "Not authorized to view this leaderboard",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
        if (!Boolean.TRUE.equals(challenge.getIsPrivate())) {
            return;
        }
        if (viewerUserId.equals(challenge.getCreatorId())
                || viewerUserId.equals(challenge.getCurrentOwnerId())) {
            return;
        }
        boolean activeParticipant = userChallengeRepository
                .findByUserIdAndChallengeId(viewerUserId, challenge.getId())
                .map(userChallenge -> Boolean.TRUE.equals(userChallenge.getActive())
                        && !Boolean.TRUE.equals(userChallenge.getBanned()))
                .orElse(false);
        if (!activeParticipant) {
            throw new ChallengeSphereException(
                    "Not authorized to view this leaderboard",
                    HttpStatus.FORBIDDEN,
                    logger
            );
        }
    }

    private List<LeaderboardEntryDTO> getLeaderboardEntriesFromCache(String challengeId, int limit) {
        List<RedisCacheServiceImpl.LeaderboardEntry> cachedEntries =
                cacheService.getTopScoresWithRank(challengeId, limit);
        if (cachedEntries.isEmpty()) {
            return List.of();
        }

        List<String> userIds = cachedEntries.stream()
                .map(RedisCacheServiceImpl.LeaderboardEntry::userId)
                .toList();
        Map<String, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<String, LeaderboardEntry> leaderboardEntryMap =
                leaderboardEntryRepository.findByChallengeIdAndUserIds(challengeId, userIds).stream()
                        .collect(Collectors.toMap(entry -> entry.getUser().getId(), entry -> entry));

        List<LeaderboardEntryDTO> entries = new ArrayList<>();
        for (RedisCacheServiceImpl.LeaderboardEntry cached : cachedEntries) {
            String cachedUserId = cached.userId();
            User user = userMap.get(cachedUserId);
            LeaderboardEntry leaderboardEntry = leaderboardEntryMap.get(cachedUserId);
            if (user == null) {
                continue;
            }
            entries.add(LeaderboardEntryDTO.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .displayName(user.getUsername())
                    .profilePictureUrl(readableProfilePic(user.getId(), user.getProfilePicUrl()))
                    .rank(cached.rank())
                    .score(cached.score())
                    .submissionCount(leaderboardEntry == null ? 0 : leaderboardEntry.getSubmissionCount())
                    .gloriesCount(leaderboardEntry == null ? 0L : (long) leaderboardEntry.getGloryPoints())
                    .currentStreak(leaderboardEntry == null ? 0 : leaderboardEntry.getStreakDays())
                    .build());
        }
        return entries;
    }

    private String readableProfilePic(String userId, String value) {
        return value == null || value.isBlank()
                ? value
                : mediaUrlService.toReadableProfilePhoto(userId, value);
    }

    private LeaderboardCursor decodeCursor(
            String cursor,
            String queryBinding
    ) throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return new LeaderboardCursor(Map.of(), 1L);
        }
        try {
            DynamoDbCursorCodec.CursorState state = cursorCodec.decode(cursor, queryBinding);
            Map<String, AttributeValue> pageState = state.shardState().get(CURSOR_PAGE_STATE);
            if (pageState == null) {
                throw new IllegalArgumentException("Cursor rank state is missing");
            }
            AttributeValue nextRankValue = pageState.get(CURSOR_NEXT_RANK);
            if (nextRankValue == null || nextRankValue.n() == null) {
                throw new IllegalArgumentException("Cursor rank is missing");
            }
            long nextRank = Long.parseLong(nextRankValue.n());
            if (nextRank < 1L) {
                throw new IllegalArgumentException("Cursor rank is outside the supported range");
            }
            return new LeaderboardCursor(state.lastEvaluatedKey(), nextRank);
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(
            String queryBinding,
            Map<String, AttributeValue> lastEvaluatedKey,
            long nextRank
    ) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(
                        lastEvaluatedKey,
                        Map.of(CURSOR_PAGE_STATE, Map.of(
                                CURSOR_NEXT_RANK,
                                AttributeValue.fromN(Long.toString(nextRank))
                        ))
                )
        );
    }

    private record LeaderboardCursor(
            Map<String, AttributeValue> lastEvaluatedKey,
            long nextRank
    ) {
    }

    private String cursorValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
    
    @Override
    @Transactional
    public void updateScore(UpdateLeaderboardScoreRequest request) throws ChallengeSphereException {
        String challengeId = request.getChallengeId();
        String userId = request.getUserId();
        Double score = request.getScore();
        
        logger.info("Updating score for challenge={}, userId={}, score={}",
            challengeId, userId, score);
        
        try {
            LeaderboardEntry entry;
            if (Boolean.TRUE.equals(request.getSetAbsolute())) {
                entry = leaderboardEntryRepository.setAbsoluteScore(
                        challengeId, userId, score
                );
                logger.info("Set absolute score {} for user {} in challenge {}", score, userId, challengeId);
            } else {
                entry = leaderboardEntryRepository.applyDeltas(
                        challengeId, userId, score, 0, 0
                );
                logger.info("Incremented score by {} for user {} in challenge {}, new score: {}",
                    score, userId, challengeId, entry.getScore());
            }
            syncLeaderboardEntry(challengeId, userId, entry);
        } catch (Exception e) {
            logger.error("Error updating score for challenge {}, user {}: {}", 
                challengeId, userId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to update leaderboard score",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }
    
    @Override
    @Transactional
    public void incrementScore(String challengeId, String userId, double points, String reason) {
        logger.debug("Incrementing score for challenge={}, userId={}, points={}",
            challengeId, userId, points);
        
        try {
            LeaderboardEntry entry = leaderboardEntryRepository.applyDeltas(
                    challengeId, userId, points, 1, 0
            );
            
            logger.info("Score updated: challenge={}, userId={}, points={}, newScore={}",
                challengeId, userId, points, entry.getScore());
            syncLeaderboardEntry(challengeId, userId, entry);
        } catch (Exception e) {
            logger.error("Error incrementing score: challenge={}, user={}, points={}",
                challengeId, userId, points);
            // Don't throw - leaderboard updates should be best effort
        }
    }

    @Override
    @Transactional
    public void decrementSubmissionScore(String challengeId, String userId, double points, String reason) {
        logger.debug("Decrementing submission score for challenge={}, userId={}, points={}",
            challengeId, userId, points);

        try {
            Optional<LeaderboardEntry> entryOptional = leaderboardEntryRepository.applyExistingDeltas(
                    challengeId, userId, -points, -1, 0
            );
            if (entryOptional.isEmpty()) {
                logger.info("No leaderboard entry found while decrementing submission score: challenge={}, user={}",
                    challengeId, userId);
                return;
            }

            LeaderboardEntry entry = entryOptional.get();
            logger.info("Submission score decremented: challenge={}, userId={}, points={}, reason={}, newScore={}",
                challengeId, userId, points, reason, entry.getScore());
            syncLeaderboardEntry(challengeId, userId, entry);
        } catch (Exception e) {
            logger.error("Error decrementing submission score: challenge={}, user={}, points={}",
                challengeId, userId, points);
            // Don't throw - leaderboard updates should be best effort.
        }
    }

    @Override
    @Transactional
    public void incrementGloryScore(String challengeId, String userId, double points, String reason) {
        adjustGloryScore(challengeId, userId, points, 1, reason);
    }

    @Override
    @Transactional
    public void decrementGloryScore(String challengeId, String userId, double points, String reason) {
        adjustGloryScore(challengeId, userId, -points, -1, reason);
    }

    private void adjustGloryScore(String challengeId, String userId, double scoreDelta, int gloryDelta, String reason) {
        logger.debug("Adjusting glory score for challenge={}, userId={}, scoreDelta={}, gloryDelta={}",
            challengeId, userId, scoreDelta, gloryDelta);

        try {
            // ChallengeSphere scoring is submission-derived; glory events only
            // update the displayed glory counter.
            LeaderboardEntry entry = leaderboardEntryRepository.applyDeltas(
                    challengeId, userId, 0.0, 0, gloryDelta
            );

            logger.info("Glory count updated without changing points: challenge={}, userId={}, gloryDelta={}, reason={}, points={}",
                challengeId, userId, gloryDelta, reason, entry.getScore());
            syncLeaderboardEntry(challengeId, userId, entry);
        } catch (Exception e) {
            logger.error("Error adjusting glory score: challenge={}, user={}, scoreDelta={}, gloryDelta={}",
                challengeId, userId, scoreDelta, gloryDelta);
            // Don't throw - leaderboard updates should be best effort.
        }
    }

    @Override
    @Transactional
    public void rebuildLeaderboard(String challengeId) throws ChallengeSphereException {
        logger.info("Rebuilding leaderboard for challenge: {}", challengeId);
        
        try {
            // Step 1: Snapshot existing entries for logging only. Rebuild must recalculate
            // from source data so historical inflated submission scores can be corrected.
            List<LeaderboardEntry> dbEntries = leaderboardEntryRepository.findActiveByChallengeId(challengeId);

            if (!dbEntries.isEmpty()) {
                logger.info("Recalculating {} existing leaderboard entries from source data for challenge {}",
                    dbEntries.size(), challengeId);
            }

            // Step 2: Rebuild from source data (UserChallenge + unique submitted days).
            List<UserChallenge> participants = userChallengeRepository.findActiveByChallengeId(challengeId);
            
            if (participants.isEmpty()) {
                logger.info("No active participants found for challenge {}", challengeId);
                return;
            }
            
            // Get challenge entity
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));
            
            List<String> userIds = participants.stream()
                    .map(UserChallenge::getUserId)
                    .collect(Collectors.toList());
            
            // Batch fetch users
            Map<String, User> userMap = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, user -> user));
            
            // Get submitted-day counts per user. A challenge day counts once even if
            // it has multiple active submissions or multiple media files.
            Map<String, Long> submissionCounts = getSubmissionCounts(challengeId, userIds);

            // Points come only from submitted media: 10 points for the first image
            // on a challenge day, then 1 point for every additional image that day.
            Map<String, Double> submissionScores = getSubmissionScores(challengeId, userIds);
            
            // Get glory counts per user
            Map<String, Long> gloryCounts = getGloryCounts(challengeId, userIds);
            
            // Calculate scores and persist to both DB and Redis
            for (String userId : userIds) {
                long submissions = submissionCounts.getOrDefault(userId, 0L);
                long glories = gloryCounts.getOrDefault(userId, 0L);
                int streak = calculateStreak(challengeId, userId);
                
                double score = submissionScores.getOrDefault(userId, 0.0);
                
                // Create/update DB entry
                User user = userMap.get(userId);
                if (user != null) {
                    leaderboardEntryRepository.replaceComputedMetrics(
                            challengeId,
                            userId,
                            score,
                            Math.toIntExact(submissions),
                            Math.toIntExact(glories),
                            streak
                    );
                    cacheService.addToLeaderboard(challengeId, userId, score);
                }
            }
            
            // Update ranks in DB
            leaderboardEntryRepository.updateRanksForChallenge(challengeId);
            
            logger.info("Rebuilt leaderboard for challenge {} with {} participants", 
                challengeId, userIds.size());
                
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error rebuilding leaderboard for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Failed to rebuild leaderboard",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }
    
    @Override
    @Transactional
    public void removeFromLeaderboard(String challengeId, String userId) {
        logger.info("Removing user {} from leaderboard for challenge {}", userId, challengeId);
        
        try {
            // Step 1: Mark as disqualified in DB (don't delete - keep history)
            leaderboardEntryRepository.disqualifyUser(challengeId, userId);
            
            logger.info("Successfully removed user {} from leaderboard for challenge {}", userId, challengeId);
        } catch (Exception e) {
            logger.error("Error removing user from leaderboard: challenge={}, user={}", challengeId, userId);
        }
    }

    @Override
    @Transactional
    public void deactivateParticipation(String challengeId, String userId) {
        leaderboardEntryRepository.markUserLeft(challengeId, userId);
        cacheService.removeFromLeaderboard(challengeId, userId);
    }

    @Override
    @Transactional
    public void reactivateParticipation(String challengeId, String userId) {
        int updated = leaderboardEntryRepository.reactivateUser(challengeId, userId);
        if (updated <= 0) {
            return;
        }
        leaderboardEntryRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(entry -> entry.getStatus() == LeaderboardEntry.LeaderboardStatus.ACTIVE)
                .ifPresent(entry -> cacheService.addToLeaderboard(challengeId, userId, entry.getScore()));
    }
    
    @Override
    @Transactional
    public void initializeUserScore(String challengeId, String userId) {
        logger.debug("Initializing leaderboard score for user {} in challenge {}", userId, challengeId);
        
        try {
            leaderboardEntryRepository.ensureEntry(challengeId, userId);
            
            logger.info("Initialized leaderboard score for user {} in challenge {}", userId, challengeId);
        } catch (Exception e) {
            logger.error("Error initializing user score: challenge={}, user={}", challengeId, userId);
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    /**
     * Get submitted-day counts per user for a challenge.
     */
    private Map<String, Long> getSubmissionCounts(String challengeId, List<String> userIds) {
        try {
            List<Submission> submissions = submissionRepository.findByChallengeIdAndUserIdIn(challengeId, userIds);
            Map<String, Set<Integer>> submittedDaysByUser = submissions.stream()
                    .filter(Submission::getActive)
                    .filter(submission -> submission.getDayNumber() != null)
                    .collect(Collectors.groupingBy(
                            Submission::getUserId,
                            Collectors.mapping(Submission::getDayNumber, Collectors.toSet())
                    ));

            return submittedDaysByUser.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> (long) entry.getValue().size()
                    ));
        } catch (Exception e) {
            logger.error("Error getting submitted-day counts for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            return new HashMap<>();
        }
    }

    private Map<String, Double> getSubmissionScores(String challengeId, List<String> userIds) {
        try {
            return submissionRepository.findByChallengeIdAndUserIdIn(challengeId, userIds).stream()
                    .filter(submission -> Boolean.TRUE.equals(submission.getActive()))
                    .collect(Collectors.groupingBy(Submission::getUserId))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> calculateSubmissionPoints(entry.getValue())
                    ));
        } catch (Exception e) {
            logger.error("Error calculating submission points for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            return new HashMap<>();
        }
    }

    private double calculateSubmissionPoints(List<Submission> submissions) {
        Map<Integer, Integer> mediaCountByDay = submissions.stream()
                .filter(submission -> Boolean.TRUE.equals(submission.getActive()))
                .filter(submission -> submission.getDayNumber() != null)
                .collect(Collectors.groupingBy(
                        Submission::getDayNumber,
                        Collectors.summingInt(submission -> countMedia(submission.getMediaUrls()))
                ));

        return mediaCountByDay.values().stream()
                .mapToDouble(mediaCount -> SUBMISSION_POINTS
                        + Math.max(0, mediaCount - 1) * ADDITIONAL_MEDIA_POINTS)
                .sum();
    }

    private int countMedia(String mediaUrls) {
        if (mediaUrls == null || mediaUrls.isBlank()) {
            return 0;
        }
        return (int) List.of(mediaUrls.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .count();
    }
    
    /**
     * Get total glory counts per user for a challenge
     */
    private Map<String, Long> getGloryCounts(String challengeId, List<String> userIds) {
        try {
            List<Submission> submissions = submissionRepository.findByChallengeIdAndUserIdIn(challengeId, userIds);
            return submissions.stream()
                    .filter(Submission::getActive)
                    .collect(Collectors.groupingBy(
                            Submission::getUserId, 
                            Collectors.summingLong(Submission::getGloriesCount)
                    ));
        } catch (Exception e) {
            logger.error("Error getting glory counts for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            return new HashMap<>();
        }
    }
    
    /** Calculate the current streak with one protected rest day per seven days. */
    private int calculateStreak(String challengeId, String userId) {
        try {
            // Get all submissions for this user-challenge, ordered by day
            List<Submission> submissions = submissionRepository.findByChallengeIdAndUserId(challengeId, userId);
            
            if (submissions.isEmpty()) {
                return 0;
            }
            
            // Get unique days with submissions, sorted descending
            Set<Integer> daysWithSubmissions = submissions.stream()
                    .filter(Submission::getActive)
                    .map(Submission::getDayNumber)
                    .collect(Collectors.toCollection(TreeSet::new));
            
            if (daysWithSubmissions.isEmpty()) {
                return 0;
            }
            
            return StreakCalculator.currentStreak(daysWithSubmissions);
        } catch (Exception e) {
            logger.error("Error calculating streak for challenge {}, user {}: {}", 
                challengeId, userId, e.getClass().getSimpleName());
            return 0;
        }
    }
    
    // ==================== DATABASE + REDIS SYNC HELPERS ====================
    
    private void syncLeaderboardEntry(
            String challengeId,
            String userId,
            LeaderboardEntry entry
    ) {
        if (entry.getStatus() == LeaderboardEntry.LeaderboardStatus.ACTIVE) {
            cacheService.addToLeaderboard(challengeId, userId, entry.getScore());
        } else {
            cacheService.removeFromLeaderboard(challengeId, userId);
        }
    }
    
    /**
     * Sync all leaderboard entries from DB to Redis for a challenge
     * Call this after server restart or Redis cache miss
     */
    @Transactional(readOnly = true)
    public void syncLeaderboardToRedis(String challengeId) {
        logger.info("Syncing leaderboard from DB for challenge: {}", challengeId);
        
        try {
            List<LeaderboardEntry> entries = leaderboardEntryRepository.findActiveByChallengeId(challengeId);
            
            logger.info("Found {} leaderboard entries for challenge {}", entries.size(), challengeId);
        } catch (Exception e) {
            logger.error("Error syncing leaderboard for challenge {}: {}", challengeId, e.getClass().getSimpleName());
        }
    }
    
    /**
     * Get leaderboard statistics from database
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLeaderboardStats(String challengeId) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            stats.put("totalParticipants", leaderboardEntryRepository.countParticipants(challengeId));
            stats.put("averageScore", leaderboardEntryRepository.getAverageScore(challengeId));
            stats.put("maxScore", leaderboardEntryRepository.getMaxScore(challengeId));
            stats.put("totalSubmissions", leaderboardEntryRepository.getTotalSubmissions(challengeId));
            stats.put("source", "database");
        } catch (Exception e) {
            logger.error("Error getting leaderboard stats for challenge {}: {}", challengeId, e.getClass().getSimpleName());
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
    
    /**
     * Update ranks for all entries in a challenge
     * Call this periodically or after batch score updates
     */
    @Transactional
    public void updateRanks(String challengeId) {
        logger.info("Updating ranks for challenge: {}", challengeId);
        try {
            leaderboardEntryRepository.updateRanksForChallenge(challengeId);
            logger.info("Successfully updated ranks for challenge {}", challengeId);
        } catch (Exception e) {
            logger.error("Error updating ranks for challenge {}: {}", challengeId, e.getClass().getSimpleName());
        }
    }
    
    /**
     * Mark all entries as completed when challenge ends
     */
    @Transactional
    public void markChallengeCompleted(String challengeId) {
        logger.info("Marking challenge {} leaderboard as completed", challengeId);
        try {
            // Update final ranks before marking completed
            leaderboardEntryRepository.updateRanksForChallenge(challengeId);
            leaderboardEntryRepository.markChallengeCompleted(challengeId);
            logger.info("Marked challenge {} leaderboard as completed", challengeId);
        } catch (Exception e) {
            logger.error("Error marking challenge {} as completed: {}", challengeId, e.getClass().getSimpleName());
        }
    }
}
