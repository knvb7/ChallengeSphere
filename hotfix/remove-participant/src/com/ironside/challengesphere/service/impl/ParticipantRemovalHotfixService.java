package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.LeaveChallengeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ParticipantRemovalHotfixService {

    private static final Logger logger = LogManager.getLogger(ParticipantRemovalHotfixService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RedisCacheServiceImpl cacheService;

    public ParticipantRemovalHotfixService(JdbcTemplate jdbcTemplate, RedisCacheServiceImpl cacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheService = cacheService;
    }

    @Transactional
    public LeaveChallengeResponse removeParticipant(
            Long challengeId,
            Long actorUserId,
            Long participantUserId,
            String reason) throws ChallengeSphereException {
        Map<String, Object> challenge;
        try {
            challenge = jdbcTemplate.queryForMap(
                    "SELECT creator_id, title FROM challengesphere.challenges WHERE id = ?",
                    challengeId);
        } catch (EmptyResultDataAccessException notFound) {
            throw new ChallengeSphereException(
                    "Challenge not found with id: " + challengeId,
                    HttpStatus.NOT_FOUND,
                    logger);
        }

        Long creatorId = ((Number) challenge.get("creator_id")).longValue();
        if (!creatorId.equals(actorUserId)) {
            throw new ChallengeSphereException(
                    "Only the challenge creator can remove participants",
                    HttpStatus.FORBIDDEN,
                    logger);
        }
        if (creatorId.equals(participantUserId)) {
            throw new ChallengeSphereException(
                    "The challenge creator cannot be removed",
                    HttpStatus.BAD_REQUEST,
                    logger);
        }

        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 10 || normalizedReason.length() > 500) {
            throw new ChallengeSphereException(
                    "A removal reason must contain between 10 and 500 characters",
                    HttpStatus.BAD_REQUEST,
                    logger);
        }

        int updated = jdbcTemplate.update("""
                        UPDATE challengesphere.user_challenges
                        SET active = false,
                            banned = true,
                            removal_reason = ?,
                            removed_at = CURRENT_TIMESTAMP,
                            removed_by_user_id = ?
                        WHERE challenge_id = ?
                          AND user_id = ?
                          AND active = true
                        """,
                normalizedReason,
                actorUserId,
                challengeId,
                participantUserId);
        if (updated == 0) {
            throw new ChallengeSphereException(
                    "User is not an active participant of this challenge",
                    HttpStatus.BAD_REQUEST,
                    logger);
        }

        jdbcTemplate.update("""
                        UPDATE challengesphere.users
                        SET challenges_accepted = GREATEST(COALESCE(challenges_accepted, 0) - 1, 0)
                        WHERE id = ?
                        """,
                participantUserId);

        try {
            cacheService.invalidateTimeline(participantUserId);
            cacheService.invalidateChallengeParticipants(challengeId);
        } catch (RuntimeException cacheFailure) {
            logger.warn(
                    "Participant {} was removed from challenge {}, but cache invalidation failed: {}",
                    participantUserId,
                    challengeId,
                    cacheFailure.getClass().getSimpleName());
        }

        String title = challenge.get("title") == null ? null : challenge.get("title").toString();
        return LeaveChallengeResponse.success(challengeId, title, List.of(participantUserId));
    }
}
