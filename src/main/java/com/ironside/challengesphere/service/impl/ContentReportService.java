package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.ContentReportRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ContentReportService {

    private static final Logger logger = LogManager.getLogger(ContentReportService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> TARGET_TYPES = Set.of("SUBMISSION", "COMMENT", "CHALLENGE");
    private static final Set<String> REASONS = Set.of(
            "CHILD_SAFETY",
            "HARASSMENT",
            "HATE",
            "NUDITY",
            "SELF_HARM",
            "SPAM",
            "VIOLENCE",
            "OTHER"
    );
    private static final Set<String> URGENT_REASONS = Set.of(
            "CHILD_SAFETY",
            "SELF_HARM",
            "VIOLENCE"
    );

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final AuthTokenStore authTokenStore;

    public ContentReportService(
            JdbcTemplate jdbcTemplate,
            UserRepository userRepository,
            AuthTokenStore authTokenStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.authTokenStore = authTokenStore;
    }

    @Transactional
    public Map<String, Object> report(Long reporterUserId, ContentReportRequest request)
            throws ChallengeSphereException {
        String targetType = normalizeTargetType(request.getTargetType());
        String reason = normalizeReason(request.getReason());
        Map<String, Object> snapshot = loadVisibleSnapshot(
                reporterUserId,
                targetType,
                request.getTargetId()
        );
        Long ownerUserId = number(snapshot.get("ownerUserId"));
        if (ownerUserId == null) {
            throw notFound();
        }
        if (reporterUserId.equals(ownerUserId)) {
            throw new ChallengeSphereException(
                    "You cannot report your own content",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        String snapshotJson;
        try {
            snapshotJson = OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (Exception error) {
            throw new ChallengeSphereException(
                    "Could not preserve the reported content",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }

        try {
            Long reportId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO challengesphere.content_reports (
                        reporter_user_id,
                        target_type,
                        target_id,
                        target_owner_user_id,
                        reason,
                        details,
                        target_snapshot
                    ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                    RETURNING id
                    """,
                    Long.class,
                    reporterUserId,
                    targetType,
                    request.getTargetId(),
                    ownerUserId,
                    reason,
                    cleanDetails(request.getDetails()),
                    snapshotJson
            );
            if (URGENT_REASONS.contains(reason)) {
                logger.error(
                        "URGENT_MODERATION_REPORT id={} reason={} targetType={} targetId={}",
                        reportId,
                        reason,
                        targetType,
                        request.getTargetId()
                );
            } else {
                logger.warn("Content report received id={} reason={} targetType={} targetId={}",
                        reportId, reason, targetType, request.getTargetId());
            }
            return Map.of(
                    "success", true,
                    "reportId", reportId,
                    "status", "OPEN",
                    "message", "Report received. Our safety team will review it."
            );
        } catch (DataIntegrityViolationException error) {
            throw new ChallengeSphereException(
                    "You already have an open report for this content",
                    HttpStatus.CONFLICT,
                    logger
            );
        }
    }

    public List<Map<String, Object>> openReports() {
        return jdbcTemplate.queryForList(
                """
                SELECT id,
                       reporter_user_id AS "reporterUserId",
                       target_type AS "targetType",
                       target_id AS "targetId",
                       target_owner_user_id AS "targetOwnerUserId",
                       reason,
                       CASE
                         WHEN reason = 'CHILD_SAFETY' THEN 'CRITICAL'
                         WHEN reason IN ('SELF_HARM', 'VIOLENCE') THEN 'URGENT'
                         ELSE 'STANDARD'
                       END AS priority,
                       details,
                       target_snapshot AS "targetSnapshot",
                       created_at AS "createdAt"
                FROM challengesphere.content_reports
                WHERE status = 'OPEN'
                ORDER BY CASE
                           WHEN reason = 'CHILD_SAFETY' THEN 0
                           WHEN reason IN ('SELF_HARM', 'VIOLENCE') THEN 1
                           ELSE 2
                         END,
                         created_at ASC
                LIMIT 200
                """
        );
    }

    @Transactional
    public Map<String, Object> resolve(Long reportId, Long reviewerId, String requestedResolution)
            throws ChallengeSphereException {
        String resolution = requestedResolution == null
                ? ""
                : requestedResolution.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DISMISSED", "REMOVED", "BANNED_USER").contains(resolution)) {
            throw new ChallengeSphereException(
                    "resolution must be DISMISSED, REMOVED, or BANNED_USER",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        Map<String, Object> report = jdbcTemplate.query(
                """
                SELECT target_type, target_id, target_owner_user_id, status
                FROM challengesphere.content_reports
                WHERE id = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? Map.of(
                        "targetType", resultSet.getString("target_type"),
                        "targetId", resultSet.getLong("target_id"),
                        "ownerUserId", resultSet.getLong("target_owner_user_id"),
                        "status", resultSet.getString("status")
                )
                        : null,
                reportId
        );
        if (report == null) {
            throw new ChallengeSphereException("Report not found", HttpStatus.NOT_FOUND, logger);
        }
        if (!"OPEN".equals(report.get("status"))) {
            throw new ChallengeSphereException("Report is already resolved", HttpStatus.CONFLICT, logger);
        }

        String targetType = String.valueOf(report.get("targetType"));
        Long targetId = number(report.get("targetId"));
        Long ownerUserId = number(report.get("ownerUserId"));
        if ("REMOVED".equals(resolution) || "BANNED_USER".equals(resolution)) {
            deactivateTarget(targetType, targetId);
        }
        if ("BANNED_USER".equals(resolution) && ownerUserId != null) {
            User user = userRepository.findById(ownerUserId).orElse(null);
            if (user != null) {
                user.setActive(false);
                user.setBannedAt(new Timestamp(System.currentTimeMillis()));
                user.setBanReason("Content report " + reportId);
                userRepository.save(user);
                authTokenStore.revokeAllForUser(ownerUserId);
            }
        }

        jdbcTemplate.update(
                """
                UPDATE challengesphere.content_reports
                SET status = 'RESOLVED',
                    resolution = ?,
                    reviewed_by = ?,
                    reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                resolution,
                reviewerId,
                reportId
        );
        return Map.of("reportId", reportId, "resolution", resolution);
    }

    private Map<String, Object> loadVisibleSnapshot(Long viewerId, String targetType, Long targetId)
            throws ChallengeSphereException {
        List<Map<String, Object>> rows = switch (targetType) {
            case "SUBMISSION" -> jdbcTemplate.queryForList(
                    """
                    SELECT s.user_id AS "ownerUserId",
                           s.challenge_id AS "challengeId",
                           s.description,
                           s.media_urls AS "mediaUrls",
                           s.created_at AS "createdAt"
                    FROM challengesphere.submissions s
                    JOIN challengesphere.challenges c ON c.id = s.challenge_id
                    WHERE s.id = ? AND s.active = TRUE AND c.active = TRUE
                      AND (
                        s.user_id = ?
                        OR (c.private = FALSE AND s.is_private = FALSE)
                        OR EXISTS (
                          SELECT 1 FROM challengesphere.user_challenges uc
                          WHERE uc.challenge_id = c.id AND uc.user_id = ?
                            AND uc.active = TRUE AND COALESCE(uc.banned, FALSE) = FALSE
                        )
                      )
                    """,
                    targetId,
                    viewerId,
                    viewerId
            );
            case "COMMENT" -> jdbcTemplate.queryForList(
                    """
                    SELECT comment.user_id AS "ownerUserId",
                           comment.submission_id AS "submissionId",
                           comment.content,
                           comment.created_at AS "createdAt"
                    FROM challengesphere.comments comment
                    JOIN challengesphere.submissions s ON s.id = comment.submission_id
                    JOIN challengesphere.challenges c ON c.id = s.challenge_id
                    WHERE comment.id = ? AND comment.active = TRUE AND s.active = TRUE AND c.active = TRUE
                      AND (
                        comment.user_id = ?
                        OR (c.private = FALSE AND s.is_private = FALSE)
                        OR EXISTS (
                          SELECT 1 FROM challengesphere.user_challenges uc
                          WHERE uc.challenge_id = c.id AND uc.user_id = ?
                            AND uc.active = TRUE AND COALESCE(uc.banned, FALSE) = FALSE
                        )
                      )
                    """,
                    targetId,
                    viewerId,
                    viewerId
            );
            case "CHALLENGE" -> jdbcTemplate.queryForList(
                    """
                    SELECT c.creator_id AS "ownerUserId",
                           c.title,
                           c.description,
                           c.benefits,
                           c.cover_photo_urls AS "coverPhotoUrls",
                           c.created_at AS "createdAt"
                    FROM challengesphere.challenges c
                    WHERE c.id = ? AND c.active = TRUE
                      AND (
                        c.creator_id = ?
                        OR c.private = FALSE
                        OR EXISTS (
                          SELECT 1 FROM challengesphere.user_challenges uc
                          WHERE uc.challenge_id = c.id AND uc.user_id = ?
                            AND uc.active = TRUE AND COALESCE(uc.banned, FALSE) = FALSE
                        )
                      )
                    """,
                    targetId,
                    viewerId,
                    viewerId
            );
            default -> List.of();
        };
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    private void deactivateTarget(String targetType, Long targetId) {
        String table = switch (targetType) {
            case "SUBMISSION" -> "submissions";
            case "COMMENT" -> "comments";
            case "CHALLENGE" -> "challenges";
            default -> throw new IllegalArgumentException("Unsupported report target");
        };
        jdbcTemplate.update("UPDATE challengesphere." + table + " SET active = FALSE WHERE id = ?", targetId);
    }

    private String normalizeTargetType(String value) throws ChallengeSphereException {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(normalized)) {
            throw new ChallengeSphereException(
                    "targetType must be SUBMISSION, COMMENT, or CHALLENGE",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return normalized;
    }

    private String normalizeReason(String value) throws ChallengeSphereException {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(normalized)) {
            throw new ChallengeSphereException("Invalid report reason", HttpStatus.BAD_REQUEST, logger);
        }
        return normalized;
    }

    private String cleanDetails(String details) {
        return StringUtils.hasText(details) ? details.trim() : null;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private ChallengeSphereException notFound() {
        return new ChallengeSphereException(
                "Content not found or not available to this account",
                HttpStatus.NOT_FOUND,
                logger
        );
    }
}
