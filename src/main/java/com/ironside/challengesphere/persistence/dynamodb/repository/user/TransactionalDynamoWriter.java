package com.ironside.challengesphere.persistence.dynamodb.repository.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes an aggregate, ordinary access projections, and fixed-key uniqueness
 * locks in one DynamoDB transaction.
 */
@Component
public class TransactionalDynamoWriter {

    private static final int MAX_ITEM_BYTES = 350 * 1024;
    private static final int MAX_TRANSACTION_ITEMS = 100;
    private static final String PROJECTION_PREFIX = "PROJECTION#";
    private static final String UNIQUE_PREFIX = "UNIQUE#";

    private final DynamoDomainStore store;
    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransactionalDynamoWriter(
            DynamoDomainStore store,
            MainTableGateway gateway,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.store = store;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void save(
            String entityType,
            String id,
            Object payload,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> previousProjections,
            Collection<DynamoProjection> uniqueProjections,
            Collection<DynamoProjection> previousUniqueProjections,
            boolean createOnly
    ) {
        save(
                entityType,
                id,
                payload,
                projections,
                previousProjections,
                uniqueProjections,
                previousUniqueProjections,
                createOnly,
                null
        );
    }

    public void save(
            String entityType,
            String id,
            Object payload,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> previousProjections,
            Collection<DynamoProjection> uniqueProjections,
            Collection<DynamoProjection> previousUniqueProjections,
            boolean createOnly,
            Long expectedVersion
    ) {
        saveAtomically(List.of(new AggregateWrite(
                entityType,
                id,
                payload,
                projections,
                previousProjections,
                uniqueProjections,
                previousUniqueProjections,
                createOnly,
                expectedVersion
        )));
    }

    /**
     * Optimistically writes several aggregates and all of their materialized
     * projections in one DynamoDB transaction.
     */
    public void saveAtomically(Collection<AggregateWrite> aggregateWrites) {
        if (aggregateWrites == null || aggregateWrites.isEmpty()) {
            throw new IllegalArgumentException("At least one aggregate write is required");
        }

        List<TransactWriteItem> actions = new ArrayList<>();
        Set<DynamoDbKey> canonicalKeys = new LinkedHashSet<>();
        List<String> aggregateNames = new ArrayList<>();
        Instant now = clock.instant();

        for (AggregateWrite write : List.copyOf(aggregateWrites)) {
            if (write == null) {
                throw new IllegalArgumentException("Aggregate write must not be null");
            }
            String normalizedType = requireText(write.entityType(), "entityType").toUpperCase();
            String normalizedId = requireText(write.id(), "id");
            DynamoDbKey canonicalKey = store.canonicalKey(normalizedType, normalizedId);
            if (!canonicalKeys.add(canonicalKey)) {
                throw new IllegalArgumentException(
                        "A DynamoDB transaction cannot write the same aggregate twice: "
                                + normalizedType + ' ' + normalizedId
                );
            }
            aggregateNames.add(normalizedType + ' ' + normalizedId);

            Optional<DynamoDbItem> previous = gateway.get(canonicalKey, true);
            if (write.createOnly() && previous.isPresent()) {
                throw new DataIntegrityViolationException(
                        normalizedType + " " + normalizedId + " already exists"
                );
            }

            long previousVersion = previous.map(DynamoDbItem::getVersion).orElse(0L);
            if (write.expectedVersion() != null
                    && (previous.isEmpty() || previousVersion != write.expectedVersion())) {
                throw new DataIntegrityViolationException(
                        "Concurrent update for " + normalizedType + ' ' + normalizedId
                );
            }
            long nextVersion = previousVersion + 1L;
            String payloadJson = encode(write.payload());

            DynamoDbItem canonical = DynamoDbItem.create(
                    canonicalKey,
                    normalizedType,
                    payloadJson,
                    now
            );
            canonical.setVersion(nextVersion);
            previous.map(DynamoDbItem::getCreatedAt).ifPresent(canonical::setCreatedAt);

            List<DynamoProjection> current = distinct(write.projections());
            List<DynamoProjection> previousRegular = distinct(write.previousProjections());
            List<DynamoProjection> currentUnique = distinct(write.uniqueProjections());
            List<DynamoProjection> previousUnique = distinct(write.previousUniqueProjections());
            Set<DynamoDbKey> retainedRegularKeys = keys(current);
            Set<DynamoDbKey> retainedUniqueKeys = keys(currentUnique);
            List<DynamoProjection> staleRegular = previousRegular.stream()
                    .filter(projection -> !retainedRegularKeys.contains(projection.key()))
                    .toList();
            List<DynamoProjection> staleUnique = previousUnique.stream()
                    .filter(projection -> !retainedUniqueKeys.contains(projection.key()))
                    .toList();

            int actionsRequired = 1 + current.size() + currentUnique.size()
                    + staleRegular.size() + staleUnique.size();
            if (actions.size() + actionsRequired > MAX_TRANSACTION_ITEMS) {
                throw new IllegalArgumentException(
                        "Atomic aggregate write needs " + (actions.size() + actionsRequired)
                                + " items; reduce materialized projections"
                );
            }

            DynamoDbTableGateway.WriteCondition canonicalCondition = previous.isEmpty()
                    ? condition(
                            "attribute_not_exists(#pk)",
                            Map.of("#pk", DynamoDbKeys.PARTITION_KEY),
                            Map.of()
                    )
                    : condition(
                            "#version = :expectedVersion",
                            Map.of("#version", DynamoDbKeys.VERSION),
                            Map.of(
                                    ":expectedVersion",
                                    AttributeValue.fromN(Long.toString(previousVersion))
                            )
                    );
            actions.add(gateway.transactionPut(canonical, canonicalCondition));

            for (DynamoProjection projection : current) {
                DynamoDbItem projectionItem = projectionItem(
                        projection,
                        PROJECTION_PREFIX + normalizedType,
                        payloadJson,
                        nextVersion,
                        canonical.getCreatedAt(),
                        now
                );
                actions.add(gateway.transactionPut(
                        projectionItem,
                        DynamoDbTableGateway.WriteCondition.none()
                ));
            }

            String uniqueOwner = UNIQUE_PREFIX + normalizedType + '#' + normalizedId;
            DynamoDbTableGateway.WriteCondition uniqueCondition = condition(
                    "attribute_not_exists(#pk) OR #owner = :owner",
                    Map.of(
                            "#pk", DynamoDbKeys.PARTITION_KEY,
                            "#owner", DynamoDbKeys.ENTITY_TYPE
                    ),
                    Map.of(":owner", AttributeValue.fromS(uniqueOwner))
            );
            for (DynamoProjection projection : currentUnique) {
                DynamoDbItem uniqueItem = projectionItem(
                        projection,
                        uniqueOwner,
                        payloadJson,
                        nextVersion,
                        canonical.getCreatedAt(),
                        now
                );
                actions.add(gateway.transactionPut(uniqueItem, uniqueCondition));
            }

            staleRegular.forEach(projection -> actions.add(gateway.transactionDelete(
                    projection.key(),
                    DynamoDbTableGateway.WriteCondition.none()
            )));
            DynamoDbTableGateway.WriteCondition ownedUniqueCondition = condition(
                    "#owner = :owner",
                    Map.of("#owner", DynamoDbKeys.ENTITY_TYPE),
                    Map.of(":owner", AttributeValue.fromS(uniqueOwner))
            );
            staleUnique.forEach(projection -> actions.add(gateway.transactionDelete(
                    projection.key(),
                    ownedUniqueCondition
            )));
        }

        try {
            gateway.transactWrite(actions, store.nextId());
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            throw new DataIntegrityViolationException(
                    "Concurrent update or duplicate unique value for "
                            + String.join(", ", aggregateNames),
                    conflict
            );
        }
    }

    public <T> Optional<Versioned<T>> loadVersioned(
            String entityType,
            String id,
            Class<T> payloadType
    ) {
        String normalizedType = requireText(entityType, "entityType").toUpperCase();
        String normalizedId = requireText(id, "id");
        return gateway.get(store.canonicalKey(normalizedType, normalizedId), true)
                .map(item -> new Versioned<>(
                        decode(item.getPayloadJson(), payloadType),
                        Optional.ofNullable(item.getVersion()).orElse(0L)
                ));
    }

    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections
    ) {
        String normalizedType = requireText(entityType, "entityType").toUpperCase();
        String normalizedId = requireText(id, "id");
        Optional<DynamoDbItem> current = gateway.get(store.canonicalKey(normalizedType, normalizedId), true);
        if (current.isEmpty()) {
            return;
        }
        delete(
                normalizedType,
                normalizedId,
                projections,
                uniqueProjections,
                Optional.ofNullable(current.get().getVersion()).orElse(0L)
        );
    }

    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections,
            long expectedVersion
    ) {
        String normalizedType = requireText(entityType, "entityType").toUpperCase();
        String normalizedId = requireText(id, "id");
        List<DynamoProjection> regular = distinct(projections);
        List<DynamoProjection> unique = distinct(uniqueProjections);
        if (regular.size() + unique.size() + 1 > MAX_TRANSACTION_ITEMS) {
            throw new IllegalArgumentException("Aggregate deletion requires asynchronous projection cleanup");
        }

        List<TransactWriteItem> actions = new ArrayList<>(regular.size() + unique.size() + 1);
        actions.add(gateway.transactionDelete(
                store.canonicalKey(normalizedType, normalizedId),
                condition(
                        "#version = :expectedVersion",
                        Map.of("#version", DynamoDbKeys.VERSION),
                        Map.of(":expectedVersion", AttributeValue.fromN(Long.toString(expectedVersion)))
                )
        ));
        regular.forEach(projection -> actions.add(gateway.transactionDelete(
                projection.key(),
                DynamoDbTableGateway.WriteCondition.none()
        )));
        String owner = UNIQUE_PREFIX + normalizedType + '#' + normalizedId;
        DynamoDbTableGateway.WriteCondition ownedCondition = condition(
                "#owner = :owner",
                Map.of("#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(owner))
        );
        unique.forEach(projection -> actions.add(gateway.transactionDelete(
                projection.key(),
                ownedCondition
        )));
        try {
            gateway.transactWrite(actions, store.nextId());
        } catch (ConditionalCheckFailedException | TransactionCanceledException alreadyChanged) {
            throw new DataIntegrityViolationException(
                    "Aggregate changed while deleting " + normalizedType + ' ' + normalizedId,
                    alreadyChanged
            );
        }
    }

    private DynamoDbItem projectionItem(
            DynamoProjection projection,
            String entityType,
            String payloadJson,
            long version,
            String createdAt,
            Instant now
    ) {
        DynamoDbItem item = DynamoDbItem.create(projection.key(), entityType, payloadJson, now);
        item.setVersion(version);
        item.setCreatedAt(createdAt);
        return item;
    }

    private String encode(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ITEM_BYTES) {
                throw new IllegalArgumentException("DynamoDB aggregate exceeds the 350 KiB guardrail");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode DynamoDB aggregate", exception);
        }
    }

    private <T> T decode(String payloadJson, Class<T> payloadType) {
        try {
            return objectMapper.readValue(payloadJson, payloadType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode DynamoDB aggregate", exception);
        }
    }

    private DynamoDbTableGateway.WriteCondition condition(
            String expression,
            Map<String, String> names,
            Map<String, AttributeValue> values
    ) {
        return DynamoDbTableGateway.WriteCondition.of(expression, names, values);
    }

    private List<DynamoProjection> distinct(Collection<DynamoProjection> projections) {
        return projections == null || projections.isEmpty()
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(projections));
    }

    private Set<DynamoDbKey> keys(List<DynamoProjection> projections) {
        LinkedHashSet<DynamoDbKey> keys = new LinkedHashSet<>();
        projections.forEach(projection -> keys.add(projection.key()));
        return Set.copyOf(keys);
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record AggregateWrite(
            String entityType,
            String id,
            Object payload,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> previousProjections,
            Collection<DynamoProjection> uniqueProjections,
            Collection<DynamoProjection> previousUniqueProjections,
            boolean createOnly,
            Long expectedVersion
    ) {
    }

    public record Versioned<T>(T value, long version) {
    }
}
