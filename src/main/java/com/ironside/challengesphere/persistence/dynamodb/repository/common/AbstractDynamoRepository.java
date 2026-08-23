package com.ironside.challengesphere.persistence.dynamodb.repository.common;

import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal CRUD surface used while the former Spring Data repositories are
 * converted to access-pattern-specific DynamoDB repositories.
 */
public abstract class AbstractDynamoRepository<T> {

    protected static final int MAX_BOUNDED_COLLECTION_ITEMS = 100_000;

    protected final DynamoDomainStore store;
    private final String entityType;
    private final Class<T> payloadType;

    protected AbstractDynamoRepository(
            DynamoDomainStore store,
            String entityType,
            Class<T> payloadType
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.entityType = requireText(entityType, "entityType").toUpperCase();
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
    }

    public T save(T entity) {
        Objects.requireNonNull(entity, "entity");
        String currentId = id(entity);
        boolean creating = currentId == null || currentId.isBlank();
        if (creating) {
            currentId = store.nextId();
            assignId(entity, currentId);
        }
        final String id = currentId;

        Optional<DynamoDomainStore.Versioned<T>> previous = store.loadVersioned(
                entityType,
                id,
                payloadType
        );
        List<DynamoProjection> previousProjections = previous
                .map(DynamoDomainStore.Versioned::value)
                .map(this::allProjections)
                .orElseGet(List::of);
        store.save(
                entityType,
                id,
                entity,
                allProjections(entity),
                previousProjections,
                creating,
                previous.map(DynamoDomainStore.Versioned::version).orElse(null)
        );
        return entity;
    }

    public List<T> saveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        entities.forEach(entity -> saved.add(save(entity)));
        return List.copyOf(saved);
    }

    public Optional<T> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.get(entityType, id, payloadType, true);
    }

    public T getReferenceById(String id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException(
                entityType + " " + id + " does not exist"
        ));
    }

    public List<T> findAllById(Iterable<String> ids) {
        List<String> requested = new ArrayList<>();
        ids.forEach(id -> {
            if (id != null && !id.isBlank()) {
                requested.add(id);
            }
        });
        return store.batchGet(entityType, requested, payloadType, true);
    }

    public boolean existsById(String id) {
        return findById(id).isPresent();
    }

    public List<T> findAll() {
        return queryProjectionPartition(allCollectionPartition(), "ITEM#", true);
    }

    public long count() {
        return findAll().size();
    }

    public void delete(T entity) {
        if (entity == null || id(entity) == null || id(entity).isBlank()) {
            return;
        }
        store.loadVersioned(entityType, id(entity), payloadType).ifPresent(current -> store.delete(
                entityType,
                id(entity),
                allProjections(current.value()),
                current.version()
        ));
    }

    public void deleteById(String id) {
        findById(id).ifPresent(this::delete);
    }

    public void deleteAllById(Iterable<? extends String> ids) {
        ids.forEach(this::deleteById);
    }

    public void deleteAll(Iterable<? extends T> entities) {
        entities.forEach(this::delete);
    }

    public void flush() {
        // DynamoDB writes are synchronous; there is no persistence context.
    }

    public T saveAndFlush(T entity) {
        return save(entity);
    }

    protected List<T> queryProjectionPartition(
            String partitionKey,
            String sortKeyPrefix,
            boolean ascending
    ) {
        return store.queryAll(
                partitionKey,
                sortKeyPrefix,
                MAX_BOUNDED_COLLECTION_ITEMS,
                ascending,
                payloadType,
                true
        );
    }

    protected DynamoPage<T> queryProjectionPage(
            String partitionKey,
            String sortKeyPrefix,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey,
            boolean ascending
    ) {
        return store.query(
                partitionKey,
                sortKeyPrefix,
                limit,
                exclusiveStartKey,
                ascending,
                payloadType,
                true
        );
    }

    protected List<DynamoProjection> allProjections(T entity) {
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(allCollectionPartition(), "ITEM#" + id(entity)));
        Collection<DynamoProjection> domainProjections = projections(entity);
        if (domainProjections != null) {
            projections.addAll(domainProjections);
        }
        return List.copyOf(projections);
    }

    protected String allCollectionPartition() {
        return DynamoAccessPatterns.collectionPk(entityType, "ALL");
    }

    protected String entityType() {
        return entityType;
    }

    protected Class<T> payloadType() {
        return payloadType;
    }

    protected abstract String id(T entity);

    protected abstract void assignId(T entity, String id);

    protected abstract Collection<DynamoProjection> projections(T entity);

    protected static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
