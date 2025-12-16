package com.ironside.challengesphere.service.impl;

import java.util.ArrayList;
import java.util.Collections;
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
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.LeaderboardEntryRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;

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
    private static final double GLORY_POINTS = 1.0;
    private static final double STREAK_BONUS_POINTS = 5.0;
    
    @Autowired
    private RedisCacheServiceImpl cacheService;
    
    @Autowired
    private ChallengeRepository challengeRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserChallengeRepository userChallengeRepository;
    
    @Autowired
    private SubmissionRepository submissionRepository;
    
    @Autowired
    private LeaderboardEntryRepository leaderboardEntryRepository;
    
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
            
            // Get top entries from Redis leaderboard
            List<RedisCacheServiceImpl.LeaderboardEntry> redisEntries = 
                    cacheService.getTopScoresWithRank(challengeId, limit);
            
            // If Redis is empty, try to rebuild from database
            if (redisEntries.isEmpty()) {
                logger.info("Leaderboard cache empty for challenge {}, rebuilding from DB", challengeId);
                rebuildLeaderboard(challengeId);
                redisEntries = cacheService.getTopScoresWithRank(challengeId, limit);
            }
            
            // If still empty, return empty leaderboard
            if (redisEntries.isEmpty()) {
                logger.info("No leaderboard entries found for challenge {}", challengeId);
                return LeaderboardResponse.success(
                        challengeId,
                        challenge.getTitle(),
                        request.getType().name(),
                        new ArrayList<>(),
                        0
                );
            }
            
            // Extract user IDs from leaderboard entries
            List<Long> userIds = redisEntries.stream()
                    .map(entry -> Long.parseLong(entry.userId()))
                    .collect(Collectors.toList());
            
            // Batch fetch user details
            Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, user -> user));
            
            // Batch fetch submission counts per user for this challenge
            Map<Long, Long> submissionCounts = getSubmissionCounts(challengeId, userIds);
            
            // Batch fetch glory counts per user for this challenge
            Map<Long, Long> gloryCounts = getGloryCounts(challengeId, userIds);
            
            // Build response entries
            List<LeaderboardEntryDTO> entries = new ArrayList<>();
            for (RedisCacheServiceImpl.LeaderboardEntry redisEntry : redisEntries) {
                Long entryUserId = Long.parseLong(redisEntry.userId());
                User user = userMap.get(entryUserId);
                
                if (user != null) {
                    entries.add(LeaderboardEntryDTO.builder()
                            .userId(entryUserId)
                            .username(user.getUsername())
                            .displayName(user.getUsername()) // Using username as display name
                            .profilePictureUrl(user.getProfilePicUrl())
                            .rank(redisEntry.rank())
                            .score(redisEntry.score())
                            .submissionCount(submissionCounts.getOrDefault(entryUserId, 0L).intValue())
                            .gloriesCount(gloryCounts.getOrDefault(entryUserId, 0L))
                            .currentStreak(calculateStreak(challengeId, entryUserId))
                            .build());
                }
            }
            
            // Get total participants
            int totalParticipants = (int) cacheService.getLeaderboardSize(challengeId);
            
            // Build response
            LeaderboardResponse response;
            
            // If userId is provided, also fetch their rank
            if (userId != null) {
                UserRankDTO userRank = getUserRank(challengeId, userId);
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
            logger.error("Error getting leaderboard for challenge {}: {}", challengeId, e.getMessage(), e);
            throw new ChallengeSphereException(
                    "Failed to get leaderboard",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }
    }
    
    @Override
    public UserRankDTO getUserRank(Long challengeId, Long userId) throws ChallengeSphereException {
        logger.debug("Getting user rank for challenge: {}, user: {}", challengeId, userId);
        
        try {
            Optional<Long> rankOpt = cacheService.getUserRank(challengeId, userId);
            Optional<Double> scoreOpt = cacheService.getUserScore(challengeId, userId);
            long totalParticipants = cacheService.getLeaderboardSize(challengeId);
            
            if (rankOpt.isEmpty() || scoreOpt.isEmpty()) {
                logger.debug("User {} not found in leaderboard for challenge {}", userId, challengeId);
                return null;
            }
            
            long rank = rankOpt.get();
            double score = scoreOpt.get();
            
            // Calculate percentile (what percentage of users is this user better than)
            double percentile = totalParticipants > 1 
                    ? ((double) (totalParticipants - rank) / (totalParticipants - 1)) * 100 
                    : 100.0;
            
            return UserRankDTO.builder()
                    .userId(userId)
                    .rank(rank)
                    .score(score)
                    .totalParticipants(totalParticipants)
                    .percentile(Math.round(percentile * 100.0) / 100.0) // Round to 2 decimal places
                    .build();
                    
        } catch (Exception e) {
            logger.error("Error getting user rank for challenge {}, user {}: {}", 
                challengeId, userId, e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    @Transactional
    public void updateScore(UpdateLeaderboardScoreRequest request) throws ChallengeSphereException {
        Long challengeId = request.getChallengeId();
        Long userId = request.getUserId();
        Double score = request.getScore();
        
        logger.info("Updating score for challenge: {}, user: {}, score: {}, reason: {}", 
            challengeId, userId, score, request.getReason());
        
        try {
            // Step 1: Persist to database first (source of truth)
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            
            if (Boolean.TRUE.equals(request.getSetAbsolute())) {
                // Set absolute score
                entry.setScore(score);
                leaderboardEntryRepository.save(entry);
                
                // Sync to Redis
                cacheService.addToLeaderboard(challengeId, userId, score);
                logger.info("Set absolute score {} for user {} in challenge {} (DB + Redis)", score, userId, challengeId);
            } else {
                // Increment score
                double newScore = entry.getScore() + score;
                entry.setScore(newScore);
                leaderboardEntryRepository.save(entry);
                
                // Sync to Redis
                cacheService.addToLeaderboard(challengeId, userId, newScore);
                logger.info("Incremented score by {} for user {} in challenge {}, new score: {} (DB + Redis)", 
                    score, userId, challengeId, newScore);
            }
        } catch (Exception e) {
            logger.error("Error updating score for challenge {}, user {}: {}", 
                challengeId, userId, e.getMessage(), e);
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
        logger.debug("Incrementing score for challenge: {}, user: {}, points: {}, reason: {}", 
            challengeId, userId, points, reason);
        
        try {
            // Step 1: Update database first (source of truth)
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            double newScore = entry.getScore() + points;
            entry.setScore(newScore);
            entry.incrementSubmissionCount();
            leaderboardEntryRepository.save(entry);
            
            // Step 2: Sync to Redis cache
            cacheService.addToLeaderboard(challengeId, userId, newScore);
            
            logger.info("Score updated: challenge={}, user={}, points={}, newScore={}, reason={} (DB + Redis)", 
                challengeId, userId, points, newScore, reason);
        } catch (Exception e) {
            logger.error("Error incrementing score: challenge={}, user={}, points={}", 
                challengeId, userId, points, e);
            // Don't throw - leaderboard updates should be best effort
        }
    }
    
    @Override
    @Transactional
    public void rebuildLeaderboard(Long challengeId) throws ChallengeSphereException {
        logger.info("Rebuilding leaderboard for challenge: {}", challengeId);
        
        try {
            // Step 1: Try to rebuild from database first (preferred - has historical data)
            List<LeaderboardEntry> dbEntries = leaderboardEntryRepository.findActiveByChallengeId(challengeId);
            
            if (!dbEntries.isEmpty()) {
                // Rebuild Redis from database
                for (LeaderboardEntry entry : dbEntries) {
                    cacheService.addToLeaderboard(challengeId, entry.getUser().getId(), entry.getScore());
                }
                logger.info("Rebuilt Redis leaderboard from database for challenge {} with {} entries", 
                    challengeId, dbEntries.size());
                return;
            }
            
            // Step 2: If DB is empty, rebuild from source data (UserChallenge + Submissions)
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
            
            // Get submission counts per user
            Map<Long, Long> submissionCounts = getSubmissionCounts(challengeId, userIds);
            
            // Get glory counts per user
            Map<Long, Long> gloryCounts = getGloryCounts(challengeId, userIds);
            
            // Calculate scores and persist to both DB and Redis
            for (Long userId : userIds) {
                long submissions = submissionCounts.getOrDefault(userId, 0L);
                long glories = gloryCounts.getOrDefault(userId, 0L);
                int streak = calculateStreak(challengeId, userId);
                
                double score = calculateOverallScore(submissions, glories, streak);
                
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
                    
                    // Sync to Redis
                    cacheService.addToLeaderboard(challengeId, userId, score);
                }
            }
            
            // Update ranks in DB
            leaderboardEntryRepository.updateRanksForChallenge(challengeId);
            
            logger.info("Rebuilt leaderboard for challenge {} with {} participants (DB + Redis)", 
                challengeId, userIds.size());
                
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error rebuilding leaderboard for challenge {}: {}", challengeId, e.getMessage(), e);
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
            
            // Step 2: Remove from Redis
            boolean removed = cacheService.removeFromLeaderboard(challengeId, userId);
            if (removed) {
                logger.info("Successfully removed user {} from leaderboard for challenge {} (DB + Redis)", userId, challengeId);
            } else {
                logger.debug("User {} was not in Redis leaderboard for challenge {}", userId, challengeId);
            }
        } catch (Exception e) {
            logger.error("Error removing user from leaderboard: challenge={}, user={}", challengeId, userId, e);
        }
    }
    
    @Override
    @Transactional
    public void initializeUserScore(Long challengeId, Long userId) {
        logger.debug("Initializing leaderboard score for user {} in challenge {}", userId, challengeId);
        
        try {
            // Create entry in DB with initial score of 0
            LeaderboardEntry entry = getOrCreateLeaderboardEntry(challengeId, userId);
            leaderboardEntryRepository.save(entry);
            
            // Add to Redis
            cacheService.addToLeaderboard(challengeId, userId, 0.0);
            logger.info("Initialized leaderboard score for user {} in challenge {} (DB + Redis)", userId, challengeId);
        } catch (Exception e) {
            logger.error("Error initializing user score: challenge={}, user={}", challengeId, userId, e);
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    /**
     * Calculate overall score based on submissions, glories, and streak
     */
    private double calculateOverallScore(long submissions, long glories, int streak) {
        double submissionScore = submissions * SUBMISSION_POINTS;
        double gloryScore = glories * GLORY_POINTS;
        double streakBonus = streak * STREAK_BONUS_POINTS;
        
        return submissionScore + gloryScore + streakBonus;
    }
    
    /**
     * Get submission counts per user for a challenge
     */
    private Map<Long, Long> getSubmissionCounts(Long challengeId, List<Long> userIds) {
        try {
            List<Submission> submissions = submissionRepository.findByChallengeIdAndUserIdIn(challengeId, userIds);
            return submissions.stream()
                    .filter(Submission::getActive)
                    .collect(Collectors.groupingBy(Submission::getUserId, Collectors.counting()));
        } catch (Exception e) {
            logger.error("Error getting submission counts for challenge {}: {}", challengeId, e.getMessage());
            return new HashMap<>();
        }
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
            logger.error("Error getting glory counts for challenge {}: {}", challengeId, e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * Calculate current streak for a user in a challenge
     * A streak is the number of consecutive days with submissions
     */
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
            
            // Find the maximum day
            int maxDay = Collections.max(daysWithSubmissions);
            
            // Count consecutive days backwards from max day
            int streak = 0;
            for (int day = maxDay; day >= 1; day--) {
                if (daysWithSubmissions.contains(day)) {
                    streak++;
                } else {
                    break;
                }
            }
            
            return streak;
        } catch (Exception e) {
            logger.error("Error calculating streak for challenge {}, user {}: {}", 
                challengeId, userId, e.getMessage());
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
    
    /**
     * Sync all leaderboard entries from DB to Redis for a challenge
     * Call this after server restart or Redis cache miss
     */
    @Transactional(readOnly = true)
    public void syncLeaderboardToRedis(Long challengeId) {
        logger.info("Syncing leaderboard from DB to Redis for challenge: {}", challengeId);
        
        try {
            List<LeaderboardEntry> entries = leaderboardEntryRepository.findActiveByChallengeId(challengeId);
            
            for (LeaderboardEntry entry : entries) {
                cacheService.addToLeaderboard(challengeId, entry.getUser().getId(), entry.getScore());
            }
            
            logger.info("Synced {} leaderboard entries to Redis for challenge {}", entries.size(), challengeId);
        } catch (Exception e) {
            logger.error("Error syncing leaderboard to Redis for challenge {}: {}", challengeId, e.getMessage(), e);
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
            logger.error("Error getting leaderboard stats for challenge {}: {}", challengeId, e.getMessage());
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
            logger.error("Error updating ranks for challenge {}: {}", challengeId, e.getMessage(), e);
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
            logger.error("Error marking challenge {} as completed: {}", challengeId, e.getMessage(), e);
        }
    }
}
