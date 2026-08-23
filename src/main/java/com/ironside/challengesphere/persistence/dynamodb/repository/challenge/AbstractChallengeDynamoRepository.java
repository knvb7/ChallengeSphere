package com.ironside.challengesphere.persistence.dynamodb.repository.challenge;

import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoVersionedAggregate;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Adds atomic uniqueness rows and repository-level keyed leases to shared CRUD. */
public abstract class AbstractChallengeDynamoRepository<T> extends AbstractDynamoRepository<T> {

    private final ChallengeTransactionalWriter writer;
    protected final ConditionalKeyLease leases;

    protected AbstractChallengeDynamoRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            String entityType,
            Class<T> payloadType
    ) {
        super(store, entityType, payloadType);
        this.writer = Objects.requireNonNull(writer, "writer");
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Override
    public T save(T entity) {
        Objects.requireNonNull(entity, "entity");
        String currentId = id(entity);
        boolean creating = currentId == null || currentId.isBlank();
        if (creating) {
            currentId = store.nextId();
            assignId(entity, currentId);
        }
        prepareForSave(entity, creating);
        String lockKey = saveLockKey(entity);
        if (lockKey != null && !lockKey.isBlank()) {
            leases.acquire(entityType(), lockKey);
        }

        try {
            Optional<DynamoDomainStore.Versioned<T>> previous = store.loadVersioned(
                    entityType(), currentId, payloadType()
            );
            Long detachedVersion = entity instanceof DynamoVersionedAggregate versioned
                    ? versioned.getDynamoVersion()
                    : null;
            Long expectedVersion = detachedVersion != null
                    ? detachedVersion
                    : previous.map(DynamoDomainStore.Versioned::version).orElse(null);
            writer.save(
                    entityType(),
                    currentId,
                    entity,
                    allProjections(entity),
                    previous.map(DynamoDomainStore.Versioned::value)
                            .map(this::allProjections).orElseGet(List::of),
                    uniqueProjections(entity),
                    previous.map(DynamoDomainStore.Versioned::value)
                            .map(this::uniqueProjections).orElseGet(List::of),
                    creating,
                    expectedVersion
            );
            if (entity instanceof DynamoVersionedAggregate versioned) {
                versioned.setDynamoVersion(previous
                        .map(DynamoDomainStore.Versioned::version)
                        .orElse(0L) + 1L);
            }
            return entity;
        } finally {
            if (lockKey != null && !lockKey.isBlank()) {
                leases.release(entityType(), lockKey);
            }
        }
    }

    @Override
    public void delete(T entity) {
        if (entity == null || id(entity) == null || id(entity).isBlank()) {
            return;
        }
        String lockKey = saveLockKey(entity);
        if (lockKey != null && !lockKey.isBlank()) {
            leases.acquire(entityType(), lockKey);
        }
        try {
            store.loadVersioned(entityType(), id(entity), payloadType()).ifPresent(current -> writer.delete(
                    entityType(),
                    id(entity),
                    allProjections(current.value()),
                    uniqueProjections(current.value()),
                    current.version()
            ));
        } finally {
            if (lockKey != null && !lockKey.isBlank()) {
                leases.release(entityType(), lockKey);
            }
        }
    }

    protected void prepareForSave(T entity, boolean creating) {
        // Optional model lifecycle hook for concrete repositories.
    }

    protected String saveLockKey(T entity) {
        return id(entity);
    }

    protected Collection<DynamoProjection> uniqueProjections(T entity) {
        return List.of();
    }

    protected Optional<T> findUnique(String partitionKey) {
        List<T> matches = store.queryAll(
                partitionKey,
                ChallengeRepositoryKeys.uniqueSk(),
                1,
                true,
                payloadType(),
                true
        );
        return matches.stream().findFirst();
    }
}
