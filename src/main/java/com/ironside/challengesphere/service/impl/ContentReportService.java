package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.ContentReportRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.model.Report;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.CommentRepository;
import com.ironside.challengesphere.repository.ReportRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DynamoDB-backed safety-report workflow. */
@Service
public class ContentReportService {

    private static final Logger logger = LogManager.getLogger(ContentReportService.class);
    private static final Set<String> TARGET_TYPES = Set.of("SUBMISSION", "COMMENT", "CHALLENGE");
    private static final Set<String> REASONS = Set.of(
            "CHILD_SAFETY", "HARASSMENT", "HATE", "NUDITY", "SELF_HARM", "SPAM", "VIOLENCE", "OTHER");
    private static final Set<String> URGENT_REASONS = Set.of("CHILD_SAFETY", "SELF_HARM", "VIOLENCE");

    private final ReportRepository reportRepository;
    private final SubmissionRepository submissionRepository;
    private final CommentRepository commentRepository;
    private final ChallengeRepository challengeRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final UserRepository userRepository;
    private final AuthTokenStore authTokenStore;
    private final ObjectMapper objectMapper;

    public ContentReportService(
            ReportRepository reportRepository,
            SubmissionRepository submissionRepository,
            CommentRepository commentRepository,
            ChallengeRepository challengeRepository,
            UserChallengeRepository userChallengeRepository,
            UserRepository userRepository,
            AuthTokenStore authTokenStore,
            ObjectMapper objectMapper
    ) {
        this.reportRepository = reportRepository;
        this.submissionRepository = submissionRepository;
        this.commentRepository = commentRepository;
        this.challengeRepository = challengeRepository;
        this.userChallengeRepository = userChallengeRepository;
        this.userRepository = userRepository;
        this.authTokenStore = authTokenStore;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> report(String reporterUserId, ContentReportRequest request)
            throws ChallengeSphereException {
        String targetType = normalizeTargetType(request.getTargetType());
        String reason = normalizeReason(request.getReason());
        Map<String, Object> snapshot = loadVisibleSnapshot(reporterUserId, targetType, request.getTargetId());
        String ownerUserId = text(snapshot.get("ownerUserId"));
        if (ownerUserId == null) throw notFound();
        if (reporterUserId.equals(ownerUserId)) {
            throw new ChallengeSphereException("You cannot report your own content", HttpStatus.BAD_REQUEST, logger);
        }

        Report report = new Report();
        report.setUserId(reporterUserId);
        report.setTargetType(targetType);
        report.setTargetId(request.getTargetId());
        report.setTargetOwnerUserId(ownerUserId);
        report.setSubmissionId(text(snapshot.get("submissionId")));
        if (report.getSubmissionId() == null && "SUBMISSION".equals(targetType)) {
            report.setSubmissionId(request.getTargetId());
        }
        report.setReason(reason);
        report.setDescription(cleanDetails(request.getDetails()));
        report.setStatus("OPEN");
        report.setActive(true);
        report.setCreatedAt(Timestamp.from(Instant.now()));
        try {
            report.setTargetSnapshot(objectMapper.writeValueAsString(snapshot));
            reportRepository.save(report);
        } catch (DataIntegrityViolationException duplicate) {
            throw new ChallengeSphereException(
                    "You already have an open report for this content", HttpStatus.CONFLICT, logger);
        } catch (Exception error) {
            throw new ChallengeSphereException(
                    "Could not preserve the reported content", HttpStatus.INTERNAL_SERVER_ERROR, logger);
        }

        if (URGENT_REASONS.contains(reason)) {
            logger.error("URGENT_MODERATION_REPORT id={} reason={} targetType={} targetId={}",
                    report.getId(), reason, targetType, request.getTargetId());
        } else {
            logger.warn("Content report received id={} reason={} targetType={} targetId={}",
                    report.getId(), reason, targetType, request.getTargetId());
        }
        return Map.of(
                "success", true,
                "reportId", report.getId(),
                "status", "OPEN",
                "message", "Report received. Our safety team will review it."
        );
    }

    public List<Map<String, Object>> openReports() {
        return reportRepository.findOpenContentReports().stream().map(this::toAdminView).toList();
    }

    public Map<String, Object> resolve(String reportId, String reviewerId, String requestedResolution)
            throws ChallengeSphereException {
        String resolution = requestedResolution == null ? "" : requestedResolution.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("DISMISSED", "REMOVED", "BANNED_USER").contains(resolution)) {
            throw new ChallengeSphereException(
                    "resolution must be DISMISSED, REMOVED, or BANNED_USER", HttpStatus.BAD_REQUEST, logger);
        }
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ChallengeSphereException("Report not found", HttpStatus.NOT_FOUND, logger));
        if (!"OPEN".equals(normalized(report.getStatus()))) {
            throw new ChallengeSphereException("Report is already resolved", HttpStatus.CONFLICT, logger);
        }

        if ("REMOVED".equals(resolution) || "BANNED_USER".equals(resolution)) {
            deactivateTarget(report.getTargetType(), report.getTargetId());
        }
        if ("BANNED_USER".equals(resolution) && report.getTargetOwnerUserId() != null) {
            User user = userRepository.findById(report.getTargetOwnerUserId()).orElse(null);
            if (user != null) {
                user.setActive(false);
                user.setBannedAt(Timestamp.from(Instant.now()));
                user.setBanReason("Content report " + reportId);
                userRepository.save(user);
                authTokenStore.revokeAllForUser(user.getId());
            }
        }

        report.setStatus("RESOLVED");
        report.setResolution(resolution);
        report.setReviewedBy(reviewerId);
        report.setReviewedAt(Timestamp.from(Instant.now()));
        reportRepository.save(report);
        return Map.of("reportId", reportId, "resolution", resolution);
    }

    private Map<String, Object> loadVisibleSnapshot(String viewerId, String targetType, String targetId)
            throws ChallengeSphereException {
        return switch (targetType) {
            case "SUBMISSION" -> submissionSnapshot(viewerId, targetId);
            case "COMMENT" -> commentSnapshot(viewerId, targetId);
            case "CHALLENGE" -> challengeSnapshot(viewerId, targetId);
            default -> throw notFound();
        };
    }

    private Map<String, Object> submissionSnapshot(String viewerId, String targetId)
            throws ChallengeSphereException {
        Submission submission = submissionRepository.findById(targetId)
                .filter(value -> Boolean.TRUE.equals(value.getActive())).orElseThrow(this::notFound);
        Challenge challenge = challengeRepository.findById(submission.getChallengeId())
                .filter(value -> Boolean.TRUE.equals(value.getActive())).orElseThrow(this::notFound);
        if (!submission.getUserId().equals(viewerId) && !canView(viewerId, challenge, submission.getIsPrivate())) {
            throw notFound();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ownerUserId", submission.getUserId());
        snapshot.put("submissionId", submission.getId());
        snapshot.put("challengeId", submission.getChallengeId());
        snapshot.put("description", submission.getDescription());
        snapshot.put("mediaUrls", submission.getMediaUrls());
        snapshot.put("createdAt", submission.getCreatedAt());
        return snapshot;
    }

    private Map<String, Object> commentSnapshot(String viewerId, String targetId)
            throws ChallengeSphereException {
        Comment comment = commentRepository.findById(targetId)
                .filter(value -> Boolean.TRUE.equals(value.getActive())).orElseThrow(this::notFound);
        Submission submission = submissionRepository.findById(comment.getSubmissionId())
                .filter(value -> Boolean.TRUE.equals(value.getActive())).orElseThrow(this::notFound);
        Challenge challenge = challengeRepository.findById(submission.getChallengeId())
                .filter(value -> Boolean.TRUE.equals(value.getActive())).orElseThrow(this::notFound);
        if (!comment.getUserId().equals(viewerId) && !canView(viewerId, challenge, submission.getIsPrivate())) {
            throw notFound();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ownerUserId", comment.getUserId());
        snapshot.put("submissionId", comment.getSubmissionId());
        snapshot.put("content", comment.getContent());
        snapshot.put("createdAt", comment.getCreatedAt());
        return snapshot;
    }

    private Map<String, Object> challengeSnapshot(String viewerId, String targetId)
            throws ChallengeSphereException {
        Challenge challenge = challengeRepository.findById(targetId)
                .filter(value -> Boolean.TRUE.equals(value.getActive())).orElseThrow(this::notFound);
        if (!challenge.getCreatorId().equals(viewerId) && !canView(viewerId, challenge, false)) throw notFound();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ownerUserId", challenge.getCreatorId());
        snapshot.put("title", challenge.getTitle());
        snapshot.put("description", challenge.getDescription());
        snapshot.put("benefits", challenge.getBenefits());
        snapshot.put("coverPhotoUrls", challenge.getCoverPhotoUrls());
        snapshot.put("createdAt", challenge.getCreatedAt());
        return snapshot;
    }

    private boolean canView(String viewerId, Challenge challenge, Boolean contentPrivate) {
        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) && !Boolean.TRUE.equals(contentPrivate)) return true;
        return userChallengeRepository.findByUserIdAndChallengeId(viewerId, challenge.getId())
                .filter(UserChallenge::getActive)
                .filter(membership -> !Boolean.TRUE.equals(membership.getBanned()))
                .isPresent();
    }

    private void deactivateTarget(String targetType, String targetId) {
        switch (normalized(targetType)) {
            case "SUBMISSION" -> submissionRepository.findById(targetId).ifPresent(ignored ->
                    submissionRepository.mutateExisting(targetId, submission -> submission.setActive(false)));
            case "COMMENT" ->
                    commentRepository.softDeleteForModerationWithSubmissionCommentCount(targetId);
            case "CHALLENGE" -> challengeRepository.findById(targetId).ifPresent(ignored ->
                    challengeRepository.mutateExisting(targetId, challenge -> challenge.setActive(false)));
            default -> throw new IllegalArgumentException("Unsupported report target");
        }
    }

    private Map<String, Object> toAdminView(Report report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", report.getId());
        result.put("reporterUserId", report.getUserId());
        result.put("targetType", report.getTargetType());
        result.put("targetId", report.getTargetId());
        result.put("targetOwnerUserId", report.getTargetOwnerUserId());
        result.put("reason", report.getReason());
        result.put("priority", priority(report.getReason()));
        result.put("details", report.getDescription());
        result.put("targetSnapshot", report.getTargetSnapshot());
        result.put("createdAt", report.getCreatedAt());
        return Collections.unmodifiableMap(result);
    }

    private String normalizeTargetType(String value) throws ChallengeSphereException {
        String normalized = normalized(value);
        if (!TARGET_TYPES.contains(normalized)) {
            throw new ChallengeSphereException(
                    "targetType must be SUBMISSION, COMMENT, or CHALLENGE", HttpStatus.BAD_REQUEST, logger);
        }
        return normalized;
    }

    private String normalizeReason(String value) throws ChallengeSphereException {
        String normalized = normalized(value);
        if (!REASONS.contains(normalized)) {
            throw new ChallengeSphereException("Invalid report reason", HttpStatus.BAD_REQUEST, logger);
        }
        return normalized;
    }

    private static String priority(String reason) {
        return switch (normalized(reason)) {
            case "CHILD_SAFETY" -> "CRITICAL";
            case "SELF_HARM", "VIOLENCE" -> "URGENT";
            default -> "STANDARD";
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String cleanDetails(String details) {
        return StringUtils.hasText(details) ? details.strip() : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private ChallengeSphereException notFound() {
        return new ChallengeSphereException(
                "Content not found or not available to this account", HttpStatus.NOT_FOUND, logger);
    }
}
