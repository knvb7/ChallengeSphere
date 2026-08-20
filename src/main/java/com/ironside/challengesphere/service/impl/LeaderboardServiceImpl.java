package com.ironside.challengesphere.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;

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
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.LeaderboardEntryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.util.StreakCalculator;

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

    @Override
    @Transactional(readOnly = true)
    public LeaderboardResponse getLeaderboard(GetLeaderboardRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        int limit = request.getLimit();
        Long userId = request.getUserId();
        
        logger.info("Getting leaderboard for challenge: {}, limit: {}, type: {}", 
            challengeId, limit, request.getType());
        
        try {
            // Validate challenge exists
            Challenge challenge = challengeRepository.findById(challengeId)
                    .orElseThrow(() -> new ChallengeSphereException(
                            "Challenge not found with id: " + challengeId,
                            HttpStatus.NOT_FOUND,
                            logger
                    ));

            requireLeaderboardAccess(challenge, request.getViewerUserId());
            
            List<LeaderboardEntry> dbEntries =
                    leaderboardEntryRepository.findTopByChallengeIdForViewer(
                            challengeId,
                            request.getViewerUserId(),
                            PageRequest.of(0, limit));

            if (dbEntries.isEmpty()) {
                logger.info("No leaderboard entries found in DB for challenge {}", challengeId);
                return LeaderboardResponse.success(
                        challengeId,
                        challenge.getTitle(),
                        request.getType().name(),
                        new ArrayList<>(),
                        0
                );
            }

            List<LeaderboardEntryDTO> entries = new ArrayList<>();
            for (int i = 0; i < dbEntries.size(); i++) {
                LeaderboardEntry le = dbEntries.get(i);
                User user = le.getUser();
                entries.add(LeaderboardEntryDTO.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getUsername())
                        .profilePictureUrl(readableProfilePic(user.getId(), user.getProfilePicUrl()))
                        .rank(i + 1L)
                        .score(le.getScore())
                        .submissionCount(le.getSubmissionCount())
                        .gloriesCount((long) le.getGloryPoints())
                        .currentStreak(le.getStreakDays())
                        .build());
                cacheService.addToLeaderboard(challengeId, user.getId(), le.getScore());
            }

            int totalParticipants = leaderboardEntryRepository
                    .countParticipantsForViewer(challengeId, request.getViewerUserId())
                    .intValue();
            
            // Build response
            LeaderboardResponse response;
            
            // If userId is provided, also fetch their rank
            if (userId != null) {
                UserRankDTO userRank = getUserRankInternal(
                        challengeId,
                        userId,
                        request.getViewerUserId()
                );
                response = LeaderboardResponse.withUserRank(
                        challengeId,
                        challenge.getTitle(),
                        request.getType().name(),
                        entries,
                        totalParticipants,
                        userRank
                );
            } else {
                response = LeaderboardResponse.success(
                        challengeId,
                        challenge.getTitle(),
                        request.getType().name(),
                        entries,
                        totalParticipants
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
    public UserRankDTO getUserRank(Long challengeId, Long userId, Long viewerUserId)
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

    private UserRankDTO getUserRankInternal(Long challengeId, Long userId, Long viewerUserId)
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
            Long rank = leaderboardEntryRepository.getUserRankForViewer(
                    challengeId,
                    userId,
                    viewerUserId
            );
            long totalParticipants = leaderboardEntryRepository
                    .countParticipantsForViewer(challengeId, viewerUserId);
            cacheService.addToLeaderboard(challengeId, userId, entry.getScore());

            long resolvedRank = rank != null ? rank : totalParticipants;
            double percentile = totalParticipants > 1
                    ? ((double) (totalParticipants - resolvedRank) / (totalParticipants - 1)) * 100
                    : 100.0;

            return UserRankDTO.builder()
                    .userId(userId)
                    .rank(resolvedRank)
                    .score(entry.getScore())
                    .totalParticipants(totalParticipants)
                    .percentile(Math.round(percentile * 100.0) / 100.0)
                    .build();

        } catch (Exception e) {
            logger.error("Error getting user rank for challenge {}, user {}: {}",
                challengeId, userId, e.getClass().getSimpleName());
            return null;
        }
    }

    private void requireLeaderboardAccess(Challenge challenge, Long viewerUserId)
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
        Long ownerUserId = challenge.getCurrentOwnerId() != null
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

    private List<LeaderboardEntryDTO> getLeaderboardEntriesFromCache(Long challengeId, int limit) {
        List<RedisCacheServiceImpl.LeaderboardEntry> cachedEntries =
                cacheService.getTopScoresWithRank(challengeId, limit);
        if (cachedEntries.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = cachedEntries.stream()
                .map(RedisCacheServiceImpl.LeaderboardEntry::userId)
                .map(Long::valueOf)
                .toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, LeaderboardEntry> leaderboardEntryMap =
                leaderboardEntryRepository.findByChallengeIdAndUserIds(challengeId, userIds).stream()
                        .collect(Collectors.toMap(entry -> entry.getUser().getId(), entry -> entry));

        List<LeaderboardEntryDTO> entries = new ArrayList<>();
        for (RedisCacheServiceImpl.LeaderboardEntry cached : cachedEntries) {
            Long cachedUserId = Long.valueOf(cached.userId());
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

    private String readableProfilePic(Long userId, String value) {
        return value == null || value.isBlank()
                ? value
                : mediaUrlService.toReadableProfilePhoto(userId, value);
    }
    
    @Override
    @Transactional
    public void updateScore(UpdateLeaderboardScoreRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        Long userId = request.getUserId();
        Double score = request.getScore();
        
        logger.info("Updating score for challenge={}, userId={}, score={}",
            challengeId, userId, score);
        
        try {
            // Step 1: Persist to database first (source of truth)
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            
            if (Boolean.TRUE.equals(request.getSetAbsolute())) {
                // Set absolute score
                entry.setScore(score);
                leaderboardEntryRepository.save(entry);
                logger.info("Set absolute score {} for user {} in challenge {}", score, userId, challengeId);
            } else {
                // Increment score
                double newScore = entry.getScore() + score;
                entry.setScore(newScore);
                leaderboardEntryRepository.save(entry);
                logger.info("Incremented score by {} for user {} in challenge {}, new score: {}",
                    score, userId, challengeId, newScore);
            }
            syncLeaderboardEntry(challengeId, userId, entry.getScore());
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
    public void incrementScore(Long challengeId, Long userId, double points, String reason) {
        logger.debug("Incrementing score for challenge={}, userId={}, points={}",
            challengeId, userId, points);
        
        try {
            // Step 1: Update database first (source of truth)
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            double newScore = entry.getScore() + points;
            entry.setScore(newScore);
            entry.incrementSubmissionCount();
            leaderboardEntryRepository.save(entry);
            
            logger.info("Score updated: challenge={}, userId={}, points={}, newScore={}",
                challengeId, userId, points, newScore);
            syncLeaderboardEntry(challengeId, userId, newScore);
        } catch (Exception e) {
            logger.error("Error incrementing score: challenge={}, user={}, points={}",
                challengeId, userId, points);
            // Don't throw - leaderboard updates should be best effort
        }
    }

    @Override
    @Transactional
    public void decrementSubmissionScore(Long challengeId, Long userId, double points, String reason) {
        logger.debug("Decrementing submission score for challenge={}, userId={}, points={}",
            challengeId, userId, points);

        try {
            Optional<LeaderboardEntry> entryOptional = leaderboardEntryRepository.findByChallengeIdAndUserId(challengeId, userId);
            if (entryOptional.isEmpty()) {
                logger.info("No leaderboard entry found while decrementing submission score: challenge={}, user={}",
                    challengeId, userId);
                return;
            }

            LeaderboardEntry entry = entryOptional.get();
            double currentScore = entry.getScore() == null ? 0.0 : entry.getScore();
            double newScore = Math.max(0.0, currentScore - points);
            entry.setScore(newScore);
            entry.decrementSubmissionCount();
            leaderboardEntryRepository.save(entry);

            logger.info("Submission score decremented: challenge={}, userId={}, points={}, reason={}, newScore={}",
                challengeId, userId, points, reason, newScore);
            syncLeaderboardEntry(challengeId, userId, newScore);
        } catch (Exception e) {
            logger.error("Error decrementing submission score: challenge={}, user={}, points={}",
                challengeId, userId, points);
            // Don't throw - leaderboard updates should be best effort.
        }
    }

    @Override
    @Transactional
    public void incrementGloryScore(Long challengeId, Long userId, double points, String reason) {
        adjustGloryScore(challengeId, userId, points, 1, reason);
    }

    @Override
    @Transactional
    public void decrementGloryScore(Long challengeId, Long userId, double points, String reason) {
        adjustGloryScore(challengeId, userId, -points, -1, reason);
    }

    private void adjustGloryScore(Long challengeId, Long userId, double scoreDelta, int gloryDelta, String reason) {
        logger.debug("Adjusting glory score for challenge={}, userId={}, scoreDelta={}, gloryDelta={}",
            challengeId, userId, scoreDelta, gloryDelta);

        try {
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            double currentScore = entry.getScore() == null ? 0.0 : entry.getScore();
            int currentGlories = entry.getGloryPoints() == null ? 0 : entry.getGloryPoints();
            int newGlories = Math.max(0, currentGlories + gloryDelta);

            entry.setGloryPoints(newGlories);
            leaderboardEntryRepository.save(entry);

            logger.info("Glory count updated without changing points: challenge={}, userId={}, gloryDelta={}, reason={}, points={}",
                challengeId, userId, gloryDelta, reason, currentScore);
            syncLeaderboardEntry(challengeId, userId, currentScore);
        } catch (Exception e) {
            logger.error("Error adjusting glory score: challenge={}, user={}, scoreDelta={}, gloryDelta={}",
                challengeId, userId, scoreDelta, gloryDelta);
            // Don't throw - leaderboard updates should be best effort.
        }
    }

    @Override
    @Transactional
    public void rebuildLeaderboard(Long challengeId) throws ChallengeSphereException {
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
            
            List<Long> userIds = participants.stream()
                    .map(UserChallenge::getUserId)
                    .collect(Collectors.toList());
            
            // Batch fetch users
            Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, user -> user));
            
            // Get submitted-day counts per user. A challenge day counts once even if
            // it has multiple active submissions or multiple media files.
            Map<Long, Long> submissionCounts = getSubmissionCounts(challengeId, userIds);

            // Points come only from submitted media: 10 points for the first image
            // on a challenge day, then 1 point for every additional image that day.
            Map<Long, Double> submissionScores = getSubmissionScores(challengeId, userIds);
            
            // Get glory counts per user
            Map<Long, Long> gloryCounts = getGloryCounts(challengeId, userIds);
            
            // Calculate scores and persist to both DB and Redis
            for (Long userId : userIds) {
                long submissions = submissionCounts.getOrDefault(userId, 0L);
                long glories = gloryCounts.getOrDefault(userId, 0L);
                int streak = calculateStreak(challengeId, userId);
                
                double score = submissionScores.getOrDefault(userId, 0.0);
                
                // Create/update DB entry
                User user = userMap.get(userId);
                if (user != null) {
                    LeaderboardEntry entry = leaderboardEntryRepository
                            .findByChallengeIdAndUserId(challengeId, userId)
                            .orElse(new LeaderboardEntry(challenge, user));
                    
                    entry.setScore(score);
                    entry.setSubmissionCount((int) submissions);
                    entry.setGloryPoints((int) glories);
                    entry.setStreakDays(streak);
                    leaderboardEntryRepository.save(entry);
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
    public void removeFromLeaderboard(Long challengeId, Long userId) {
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
    public void deactivateParticipation(Long challengeId, Long userId) {
        leaderboardEntryRepository.markUserLeft(challengeId, userId);
        leaderboardEntryRepository.updateRanksForChallenge(challengeId);
        cacheService.removeFromLeaderboard(challengeId, userId);
    }

    @Override
    @Transactional
    public void reactivateParticipation(Long challengeId, Long userId) {
        int updated = leaderboardEntryRepository.reactivateUser(challengeId, userId);
        if (updated <= 0) {
            return;
        }
        leaderboardEntryRepository.updateRanksForChallenge(challengeId);
        leaderboardEntryRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(entry -> entry.getStatus() == LeaderboardEntry.LeaderboardStatus.ACTIVE)
                .ifPresent(entry -> cacheService.addToLeaderboard(challengeId, userId, entry.getScore()));
    }
    
    @Override
    @Transactional
    public void initializeUserScore(Long challengeId, Long userId) {
        logger.debug("Initializing leaderboard score for user {} in challenge {}", userId, challengeId);
        
        try {
            // Create entry in DB with initial score of 0
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            leaderboardEntryRepository.save(entry);
            
            logger.info("Initialized leaderboard score for user {} in challenge {}", userId, challengeId);
        } catch (Exception e) {
            logger.error("Error initializing user score: challenge={}, user={}", challengeId, userId);
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    /**
     * Get submitted-day counts per user for a challenge.
     */
    private Map<Long, Long> getSubmissionCounts(Long challengeId, List<Long> userIds) {
        try {
            List<Submission> submissions = submissionRepository.findByChallengeIdAndUserIdIn(challengeId, userIds);
            Map<Long, Set<Integer>> submittedDaysByUser = submissions.stream()
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

    private Map<Long, Double> getSubmissionScores(Long challengeId, List<Long> userIds) {
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
    private Map<Long, Long> getGloryCounts(Long challengeId, List<Long> userIds) {
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
    private int calculateStreak(Long challengeId, Long userId) {
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
    
    /**
     * Get or create a leaderboard entry in the database
     */
    private LeaderboardEntry getOrCreateLeaderboardEntry(Long challengeId, Long userId) {
        return leaderboardEntryRepository.findByChallengeIdAndUserId(challengeId, userId)
                .orElseGet(() -> {
                    Challenge challenge = challengeRepository.findById(challengeId)
                            .orElseThrow(() -> new RuntimeException("Challenge not found: " + challengeId));
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
                    return new LeaderboardEntry(challenge, user);
                });
    }

    private void syncLeaderboardEntry(Long challengeId, Long userId, double score) {
        leaderboardEntryRepository.updateRanksForChallenge(challengeId);
        boolean active = leaderboardEntryRepository.findByChallengeIdAndUserId(challengeId, userId)
                .map(entry -> entry.getStatus() == LeaderboardEntry.LeaderboardStatus.ACTIVE)
                .orElse(false);
        if (active) {
            cacheService.addToLeaderboard(challengeId, userId, score);
        } else {
            cacheService.removeFromLeaderboard(challengeId, userId);
        }
    }
    
    /**
     * Sync all leaderboard entries from DB to Redis for a challenge
     * Call this after server restart or Redis cache miss
     */
    @Transactional(readOnly = true)
    public void syncLeaderboardToRedis(Long challengeId) {
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
    public Map<String, Object> getLeaderboardStats(Long challengeId) {
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
    public void updateRanks(Long challengeId) {
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
    public void markChallengeCompleted(Long challengeId) {
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
