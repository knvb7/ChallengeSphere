package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Report;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

@Repository
public class ReportRepository extends AbstractDynamoRepository<Report> {

    private final TransactionalDynamoWriter writer;

    public ReportRepository(DynamoDomainStore store, TransactionalDynamoWriter writer) {
        super(store, "REPORT", Report.class);
        this.writer = writer;
    }

    @Override
    public Report save(Report report) {
        if (report.getCreatedAt() == null) {
            report.setCreatedAt(Timestamp.from(Instant.now()));
        }
        boolean creating = report.getId() == null || report.getId().isBlank();
        if (creating) report.setId(store.nextId());
        java.util.Optional<TransactionalDynamoWriter.Versioned<Report>> previous =
                writer.loadVersioned(entityType(), report.getId(), Report.class);
        writer.save(
                entityType(),
                report.getId(),
                report,
                allProjections(report),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                uniqueProjections(report),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(ReportRepository::uniqueProjections).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return report;
    }

    public List<Report> findBySubmissionId(String submissionId) {
        return queryProjectionPartition("SUBMISSION#" + requireText(submissionId, "submissionId"), "REPORT#", true);
    }

    public List<Report> findByUserId(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "REPORT#", true);
    }

    public List<Report> findByStatus(String status) {
        return queryProjectionPartition("REPORT#STATUS#" + normalized(status), "REPORT#", true);
    }

    public List<Report> findByReason(String reason) {
        return queryProjectionPartition("REPORT#REASON#" + normalized(reason), "REPORT#", true);
    }

    public List<Report> findBySubmissionIdAndUserId(String submissionId, String userId) {
        return findBySubmissionId(submissionId).stream().filter(report -> userId.equals(report.getUserId())).toList();
    }

    public List<Report> findAllActiveReportsOrderByCreatedAtDesc() {
        return queryProjectionPartition("REPORT#ACTIVE", "REPORT#", false);
    }

    public List<Report> findPendingReportsOrderByCreatedAtAsc() {
        return queryProjectionPartition("REPORT#STATUS#PENDING", "REPORT#", true).stream()
                .filter(report -> Boolean.TRUE.equals(report.getActive())).toList();
    }

    public List<Report> findOpenContentReports() {
        List<Report> reports = new ArrayList<>();
        reports.addAll(queryProjectionPartition("CONTENT_REPORT#OPEN#CRITICAL", "REPORT#", true));
        reports.addAll(queryProjectionPartition("CONTENT_REPORT#OPEN#URGENT", "REPORT#", true));
        reports.addAll(queryProjectionPartition("CONTENT_REPORT#OPEN#STANDARD", "REPORT#", true));
        return reports.stream().limit(200).toList();
    }

    public java.util.Optional<Report> findOpenByReporterAndTarget(
            String reporterUserId, String targetType, String targetId
    ) {
        return queryProjectionPartition(
                duplicatePartition(reporterUserId, targetType, targetId), "OPEN#", true
        ).stream().findFirst();
    }

    public List<Report> findByTarget(String targetType, String targetId) {
        return queryProjectionPartition(
                "CONTENT#" + normalized(targetType) + '#' + requireText(targetId, "targetId"),
                "REPORT#",
                true
        );
    }

    public List<Report> findReviewedReports() {
        return queryProjectionPartition("REPORT#STATUS#REVIEWED", "REPORT#", false);
    }

    public Long countActiveReportsBySubmissionId(String submissionId) {
        return findBySubmissionId(submissionId).stream().filter(report -> Boolean.TRUE.equals(report.getActive())).count();
    }

    public Long countReportsByStatus(String status) {
        return (long) findByStatus(status).size();
    }

    public void updateReportStatus(String id, String status) {
        findById(id).ifPresent(report -> {
            report.setStatus(status);
            save(report);
        });
    }

    public void updateReportActiveStatus(String id, Boolean active) {
        findById(id).ifPresent(report -> {
            report.setActive(active);
            save(report);
        });
    }

    @Override
    protected String id(Report entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(Report entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(Report entity) {
        String created = entity.getCreatedAt() == null ? entity.getId() : entity.getCreatedAt().toInstant().toString();
        String sort = "REPORT#" + created + '#' + entity.getId();
        List<DynamoProjection> projections = new ArrayList<>();
        if (entity.getSubmissionId() != null) projections.add(DynamoProjection.of("SUBMISSION#" + entity.getSubmissionId(), sort));
        if (entity.getUserId() != null) projections.add(DynamoProjection.of("USER#" + entity.getUserId(), sort));
        projections.add(DynamoProjection.of("REPORT#STATUS#" + normalized(entity.getStatus()), sort));
        projections.add(DynamoProjection.of("REPORT#REASON#" + normalized(entity.getReason()), sort));
        if (Boolean.TRUE.equals(entity.getActive())) projections.add(DynamoProjection.of("REPORT#ACTIVE", sort));
        if (entity.getTargetType() != null && entity.getTargetId() != null) {
            projections.add(DynamoProjection.of(
                    "CONTENT#" + normalized(entity.getTargetType()) + '#' + entity.getTargetId(), sort));
            if (entity.getUserId() != null) {
                String duplicatePartition = duplicatePartition(
                        entity.getUserId(), entity.getTargetType(), entity.getTargetId());
                String duplicateSort = "OPEN".equals(normalized(entity.getStatus()))
                        ? "OPEN#" + entity.getId()
                        : "RESOLVED#" + entity.getId();
                projections.add(DynamoProjection.of(duplicatePartition, duplicateSort));
            }
            if ("OPEN".equals(normalized(entity.getStatus()))) {
                projections.add(DynamoProjection.of(
                        "CONTENT_REPORT#OPEN#" + priority(entity.getReason()), sort));
            }
        }
        return projections;
    }

    private static String duplicatePartition(String reporterUserId, String targetType, String targetId) {
        return "CONTENT_REPORT#REPORTER#" + requireText(reporterUserId, "reporterUserId")
                + "#TARGET#" + normalized(targetType) + '#' + requireText(targetId, "targetId");
    }

    private static List<DynamoProjection> uniqueProjections(Report report) {
        if (!"OPEN".equals(normalized(report.getStatus()))
                || report.getUserId() == null
                || report.getTargetType() == null
                || report.getTargetId() == null) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                duplicatePartition(report.getUserId(), report.getTargetType(), report.getTargetId()),
                "OPEN#LOCK"
        ));
    }

    /** Exact projection set used by the offline cutover verifier. */
    public List<DynamoProjection> projectionsForVerification(Report report) {
        List<DynamoProjection> result = new ArrayList<>(allProjections(report));
        result.addAll(uniqueProjections(report));
        return List.copyOf(result);
    }

    private static String priority(String reason) {
        return switch (normalized(reason)) {
            case "CHILD_SAFETY" -> "CRITICAL";
            case "SELF_HARM", "VIOLENCE" -> "URGENT";
            default -> "STANDARD";
        };
    }

    private static String normalized(String value) {
        return (value == null || value.isBlank() ? "UNKNOWN" : value.strip()).toUpperCase(Locale.ROOT);
    }
}
