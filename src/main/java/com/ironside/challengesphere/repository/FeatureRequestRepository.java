package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.FeatureRequest;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

@Repository
public class FeatureRequestRepository extends AbstractDynamoRepository<FeatureRequest> {

    public FeatureRequestRepository(DynamoDomainStore store) {
        super(store, "FEATURE_REQUEST", FeatureRequest.class);
    }

    @Override
    public FeatureRequest save(FeatureRequest request) {
        Timestamp now = Timestamp.from(Instant.now());
        if (request.getCreatedAt() == null) {
            request.setCreatedAt(now);
        }
        request.setUpdatedAt(now);
        return super.save(request);
    }

    public List<FeatureRequest> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return store.queryAll(
                "USER#" + userId,
                "FEATURE_REQUEST#",
                UserDynamoKeys.MAX_QUERY_ITEMS,
                true,
                FeatureRequest.class,
                true
        );
    }

    @Override
    protected String id(FeatureRequest entity) {
        return entity.getId();
    }

    @Override
    protected void assignId(FeatureRequest entity, String id) {
        entity.setId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(FeatureRequest entity) {
        List<DynamoProjection> result = new ArrayList<>();
        String created = entity.getCreatedAt() == null ? entity.getId() : entity.getCreatedAt().toInstant().toString();
        if (entity.getUserId() != null) {
            result.add(DynamoProjection.of("USER#" + entity.getUserId(), "FEATURE_REQUEST#" + created + '#' + entity.getId()));
        }
        result.add(DynamoProjection.of(
                "FEATURE_REQUEST#STATUS#" + normalize(entity.getStatus(), "NEW"),
                "CREATED#" + created + '#' + entity.getId()
        ));
        return result;
    }

    private static String normalize(String value, String fallback) {
        return (value == null || value.isBlank() ? fallback : value).strip().toUpperCase(Locale.ROOT);
    }
}
