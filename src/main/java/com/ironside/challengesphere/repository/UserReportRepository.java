package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserReport;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

@Repository
public class UserReportRepository extends AbstractDynamoRepository<UserReport> {

    private static final String ENTITY_TYPE = "USER_REPORT";

    public UserReportRepository(DynamoDomainStore store) {
        super(store, ENTITY_TYPE, UserReport.class);
    }

    @Override
    public UserReport save(UserReport report) {
        if (report.getCreatedAt() == null) {
            report.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        }
        if (report.getStatus() == null || report.getStatus().isBlank()) {
            report.setStatus("OPEN");
        }
        return super.save(report);
    }

    public List<UserReport> findByStatusOrderByCreatedAtAsc(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return store.queryAll(
                statusPartition(status),
                "REPORT#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                UserReport.class,
                true
        );
    }

    public List<UserReport> findByParticipantUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, UserReport> reports = new LinkedHashMap<>();
        queryParticipant("REPORTER", userId).forEach(report -> reports.put(report.getId(), report));
        queryParticipant("REPORTED", userId).forEach(report -> reports.put(report.getId(), report));
        return List.copyOf(reports.values());
    }

    @Override
    protected String id(UserReport entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(UserReport entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(UserReport report) {
        String sortKey = UserDynamoKeys.chronologicalSk(
                "REPORT", report.getCreatedAt(), report.getId()
        );
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(UserDynamoKeys.projection(statusPartition(report.getStatus()), sortKey));
        if (report.getReporterUserId() != null && !report.getReporterUserId().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    participantPartition("REPORTER", report.getReporterUserId()), sortKey
            ));
        }
        if (report.getReportedUserId() != null && !report.getReportedUserId().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    participantPartition("REPORTED", report.getReportedUserId()), sortKey
            ));
        }
        return List.copyOf(projections);
    }

    private List<UserReport> queryParticipant(String role, String userId) {
        return store.queryAll(
                participantPartition(role, userId),
                "REPORT#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                UserReport.class,
                true
        );
    }

    private String participantPartition(String role, String userId) {
        return DynamoDbKeys.join("USER_REPORT", role, userId);
    }

    private String statusPartition(String status) {
        return DynamoDbKeys.join("USER_REPORT_STATUS", UserDynamoKeys.normalize(status).toUpperCase());
    }
}
