package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ModerationFlag;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Repository
public class ModerationFlagRepository extends AbstractDynamoRepository<ModerationFlag> {

    public ModerationFlagRepository(DynamoDomainStore store) {
        super(store, "MODERATION_FLAG", ModerationFlag.class);
    }

    @Override
    public ModerationFlag save(ModerationFlag flag) {
        if (flag.getCreatedAt() == null) {
            flag.setCreatedAt(Timestamp.from(Instant.now()));
        }
        return super.save(flag);
    }

    public List<ModerationFlag> findPending() {
        return queryProjectionPartition("MODERATION#PENDING", "FLAG#", true);
    }

    public List<ModerationFlag> findByUserId(String userId) {
        return queryProjectionPartition("USER#" + requireText(userId, "userId"), "MODERATION_FLAG#", false);
    }

    @Override
    protected String id(ModerationFlag entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(ModerationFlag entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(ModerationFlag entity) {
        String created = entity.getCreatedAt() == null ? entity.getId() : entity.getCreatedAt().toInstant().toString();
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(
                entity.getReviewedAt() == null ? "MODERATION#PENDING" : "MODERATION#REVIEWED",
                "FLAG#" + created + '#' + entity.getId()
        ));
        if (entity.getUserId() != null) {
            projections.add(DynamoProjection.of("USER#" + entity.getUserId(), "MODERATION_FLAG#" + created + '#' + entity.getId()));
        }
        if (entity.getSubmissionId() != null) {
            projections.add(DynamoProjection.of("SUBMISSION#" + entity.getSubmissionId(), "MODERATION_FLAG#" + created + '#' + entity.getId()));
        }
        return projections;
    }
}
