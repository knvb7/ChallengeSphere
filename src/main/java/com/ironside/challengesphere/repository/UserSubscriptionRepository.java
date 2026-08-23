package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserSubscription;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.TransactionalDynamoWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class UserSubscriptionRepository extends AbstractDynamoRepository<UserSubscription> {

    private static final String ENTITY_TYPE = "USER_SUBSCRIPTION";

    private final TransactionalDynamoWriter writer;

    public UserSubscriptionRepository(DynamoDomainStore store, TransactionalDynamoWriter writer) {
        super(store, ENTITY_TYPE, UserSubscription.class);
        this.writer = writer;
    }

    @Override
    public UserSubscription save(UserSubscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("User subscription must not be null");
        }
        boolean creating = subscription.getUserId() == null || subscription.getUserId().isBlank();
        if (creating) {
            subscription.setUserId(store.nextId());
        }
        requireText(subscription.getAppUserId(), "appUserId");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (subscription.getCreatedAt() == null) {
            subscription.setCreatedAt(now);
        }
        if (subscription.getLastSyncedAt() == null) {
            subscription.setLastSyncedAt(now);
        }
        subscription.setUpdatedAt(now);

        Optional<TransactionalDynamoWriter.Versioned<UserSubscription>> previous =
                writer.loadVersioned(ENTITY_TYPE, subscription.getUserId(), UserSubscription.class);
        writer.save(
                ENTITY_TYPE,
                subscription.getUserId(),
                subscription,
                allProjections(subscription),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::allProjections).orElseGet(List::of),
                appUserUniqueProjection(subscription),
                previous.map(TransactionalDynamoWriter.Versioned::value)
                        .map(this::appUserUniqueProjection).orElseGet(List::of),
                creating,
                previous.map(TransactionalDynamoWriter.Versioned::version).orElse(null)
        );
        return subscription;
    }

    @Override
    public void delete(UserSubscription subscription) {
        if (subscription == null || subscription.getUserId() == null
                || subscription.getUserId().isBlank()) {
            return;
        }
        writer.loadVersioned(ENTITY_TYPE, subscription.getUserId(), UserSubscription.class)
                .ifPresent(current -> writer.delete(
                        ENTITY_TYPE,
                        subscription.getUserId(),
                        allProjections(current.value()),
                        appUserUniqueProjection(current.value()),
                        current.version()
                ));
    }

    public Optional<UserSubscription> findByAppUserId(String appUserId) {
        if (appUserId == null || appUserId.isBlank()) {
            return Optional.empty();
        }
        return store.queryAll(
                        UserDynamoKeys.uniquePk(ENTITY_TYPE, "APP_USER_ID", appUserId),
                        UserDynamoKeys.LOOKUP,
                        1,
                        true,
                        UserSubscription.class,
                        true
                ).stream()
                .findFirst();
    }

    @Override
    protected String id(UserSubscription entity) {
        return entity.getUserId();
    }

    @Override
    protected void assignId(UserSubscription entity, String id) {
        entity.setUserId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(UserSubscription subscription) {
        List<DynamoProjection> projections = new ArrayList<>(2);
        projections.add(UserDynamoKeys.projection(
                DynamoAccessPatterns.ownerPk("USER", subscription.getUserId()),
                "SUBSCRIPTION#" + subscription.getUserId()
        ));
        if (subscription.getStatus() != null && !subscription.getStatus().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.collection(ENTITY_TYPE, subscription.getStatus()),
                    UserDynamoKeys.ITEM_PREFIX + subscription.getUserId()
            ));
        }
        return projections;
    }

    private List<DynamoProjection> appUserUniqueProjection(UserSubscription subscription) {
        return List.of(UserDynamoKeys.projection(
                UserDynamoKeys.uniquePk(ENTITY_TYPE, "APP_USER_ID", subscription.getAppUserId()),
                UserDynamoKeys.LOOKUP
        ));
    }
}
