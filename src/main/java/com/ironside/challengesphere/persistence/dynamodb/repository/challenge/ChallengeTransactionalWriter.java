package com.ironside.challengesphere.persistence.dynamodb.repository.challenge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** Atomically writes an aggregate, its access rows, and fixed-key uniqueness rows. */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengeTransactionalWriter {

    private static final int MAX_ITEM_BYTES = 350 * 1024;
    private static final int MAX_TRANSACTION_ITEMS = 100;
    private static final String PROJECTION_PREFIX = "PROJECTION#";
    private static final String UNIQUE_PREFIX = "UNIQUE#";

    private final DynamoDomainStore store;
    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChallengeTransactionalWriter(
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
                List.of()
        );
    }

    /**
     * Saves an aggregate together with domain-owned transaction actions.
     *
     * <p>The additional actions are deliberately assembled by the owning
     * repository. This keeps fixed-key invariants, such as participation
     * quota slots, in the same transaction as the canonical aggregate without
     * teaching this generic writer about those invariants.</p>
     */
    public void save(
            String entityType,
            String id,
            Object payload,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> previousProjections,
            Collection<DynamoProjection> uniqueProjections,
            Collection<DynamoProjection> previousUniqueProjections,
            boolean createOnly,
            Collection<TransactWriteItem> additionalActions
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
                null,
                additionalActions
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
        save(
                entityType,
                id,
                payload,
                projections,
                previousProjections,
                uniqueProjections,
                previousUniqueProjections,
                createOnly,
                expectedVersion,
                List.of()
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
            Long expectedVersion,
            Collection<TransactWriteItem> additionalActions
    ) {
        String type = requireText(entityType, "entityType").toUpperCase();
        String aggregateId = requireText(id, "id");
        DynamoDbKey canonicalKey = store.canonicalKey(type, aggregateId);
        Optional<DynamoDbItem> previous = gateway.get(canonicalKey, true);
        if (createOnly && previous.isPresent()) {
            throw new DataIntegrityViolationException(type + " " + aggregateId + " already exists");
        }

        long previousVersion = previous.map(DynamoDbItem::getVersion).orElse(0L);
        if (expectedVersion != null
                && (previous.isEmpty() || previousVersion != expectedVersion)) {
            throw new ChallengeWriteConflictException(
                    "Concurrent update for " + type + ' ' + aggregateId,
                    null
            );
        }
        long nextVersion = previousVersion + 1L;
        Instant now = clock.instant();
        String payloadJson = encode(payload);
        DynamoDbItem canonical = DynamoDbItem.create(canonicalKey, type, payloadJson, now);
        canonical.setVersion(nextVersion);
        previous.map(DynamoDbItem::getCreatedAt).ifPresent(canonical::setCreatedAt);

        List<DynamoProjection> current = distinct(projections);
        List<DynamoProjection> old = distinct(previousProjections);
        List<DynamoProjection> currentUnique = distinct(uniqueProjections);
        List<DynamoProjection> oldUnique = distinct(previousUniqueProjections);
        Set<DynamoDbKey> retained = keys(current);
        Set<DynamoDbKey> retainedUnique = keys(currentUnique);
        List<DynamoProjection> stale = old.stream()
                .filter(projection -> !retained.contains(projection.key()))
                .toList();
        List<DynamoProjection> staleUnique = oldUnique.stream()
                .filter(projection -> !retainedUnique.contains(projection.key()))
                .toList();

        List<TransactWriteItem> extras = additionalActions == null
                ? List.of()
                : List.copyOf(additionalActions);
        int actionsRequired = 1 + current.size() + currentUnique.size()
                + stale.size() + staleUnique.size() + extras.size();
        if (actionsRequired > MAX_TRANSACTION_ITEMS) {
            throw new IllegalArgumentException(
                    "Aggregate write needs " + actionsRequired + " DynamoDB actions; reduce indexed values"
            );
        }

        List<TransactWriteItem> actions = new ArrayList<>(actionsRequired);
        DynamoDbTableGateway.WriteCondition canonicalCondition = previous.isEmpty()
                ? condition("attribute_not_exists(#pk)", Map.of("#pk", DynamoDbKeys.PARTITION_KEY), Map.of())
                : condition(
                        "#version = :expected",
                        Map.of("#version", DynamoDbKeys.VERSION),
                        Map.of(":expected", AttributeValue.fromN(Long.toString(previousVersion)))
                );
        actions.add(gateway.transactionPut(canonical, canonicalCondition));

        current.forEach(projection -> actions.add(gateway.transactionPut(
                projectionItem(projection, PROJECTION_PREFIX + type, payloadJson, nextVersion, canonical, now),
                DynamoDbTableGateway.WriteCondition.none()
        )));

        String uniqueOwner = UNIQUE_PREFIX + type + '#' + aggregateId;
        DynamoDbTableGateway.WriteCondition uniqueCondition = condition(
                "attribute_not_exists(#pk) OR #owner = :owner",
                Map.of("#pk", DynamoDbKeys.PARTITION_KEY, "#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(uniqueOwner))
        );
        currentUnique.forEach(projection -> actions.add(gateway.transactionPut(
                projectionItem(projection, uniqueOwner, payloadJson, nextVersion, canonical, now),
                uniqueCondition
        )));

        stale.forEach(projection -> actions.add(gateway.transactionDelete(
                projection.key(), DynamoDbTableGateway.WriteCondition.none()
        )));
        DynamoDbTableGateway.WriteCondition ownedUnique = condition(
                "#owner = :owner",
                Map.of("#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(uniqueOwner))
        );
        staleUnique.forEach(projection -> actions.add(gateway.transactionDelete(projection.key(), ownedUnique)));
        actions.addAll(extras);

        try {
            gateway.transactWrite(actions, store.nextId());
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            throw new ChallengeWriteConflictException(
                    "Concurrent update or duplicate unique value for " + type + ' ' + aggregateId,
                    conflict
            );
        }
    }

    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections
    ) {
        String type = requireText(entityType, "entityType").toUpperCase();
        String aggregateId = requireText(id, "id");
        Optional<DynamoDbItem> current = gateway.get(store.canonicalKey(type, aggregateId), true);
        if (current.isEmpty()) {
            return;
        }
        delete(
                type,
                aggregateId,
                projections,
                uniqueProjections,
                Optional.ofNullable(current.get().getVersion()).orElse(0L),
                List.of()
        );
    }

    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections,
            long expectedVersion
    ) {
        delete(entityType, id, projections, uniqueProjections, expectedVersion, List.of());
    }

    /** Deletes an aggregate and domain-owned fixed-key rows atomically. */
    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections,
            Collection<TransactWriteItem> additionalActions
    ) {
        String type = requireText(entityType, "entityType").toUpperCase();
        String aggregateId = requireText(id, "id");
        Optional<DynamoDbItem> current = gateway.get(store.canonicalKey(type, aggregateId), true);
        if (current.isEmpty()) {
            return;
        }
        delete(
                type,
                aggregateId,
                projections,
                uniqueProjections,
                Optional.ofNullable(current.get().getVersion()).orElse(0L),
                additionalActions
        );
    }

    /** Deletes the exact aggregate version that supplied projections and extra invariants. */
    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections,
            long expectedVersion,
            Collection<TransactWriteItem> additionalActions
    ) {
        String type = requireText(entityType, "entityType").toUpperCase();
        String aggregateId = requireText(id, "id");
        List<DynamoProjection> regular = distinct(projections);
        List<DynamoProjection> unique = distinct(uniqueProjections);
        List<TransactWriteItem> extras = additionalActions == null
                ? List.of()
                : List.copyOf(additionalActions);
        if (regular.size() + unique.size() + extras.size() + 1 > MAX_TRANSACTION_ITEMS) {
            throw new IllegalArgumentException("Aggregate deletion requires asynchronous projection cleanup");
        }

        List<TransactWriteItem> actions = new ArrayList<>(
                regular.size() + unique.size() + extras.size() + 1
        );
        actions.add(gateway.transactionDelete(
                store.canonicalKey(type, aggregateId),
                condition(
                        "#version = :expected",
                        Map.of("#version", DynamoDbKeys.VERSION),
                        Map.of(":expected", AttributeValue.fromN(Long.toString(expectedVersion)))
                )
        ));
        regular.forEach(projection -> actions.add(gateway.transactionDelete(
                projection.key(), DynamoDbTableGateway.WriteCondition.none()
        )));
        String uniqueOwner = UNIQUE_PREFIX + type + '#' + aggregateId;
        DynamoDbTableGateway.WriteCondition ownedUnique = condition(
                "#owner = :owner",
                Map.of("#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(uniqueOwner))
        );
        unique.forEach(projection -> actions.add(gateway.transactionDelete(projection.key(), ownedUnique)));
        actions.addAll(extras);
        try {
            gateway.transactWrite(actions, store.nextId());
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            throw new ChallengeWriteConflictException(
                    "Aggregate changed while deleting " + type + ' ' + aggregateId,
                    conflict
            );
        }
    }

    private DynamoDbItem projectionItem(
            DynamoProjection projection,
            String itemType,
            String payloadJson,
            long version,
            DynamoDbItem canonical,
            Instant now
    ) {
        DynamoDbItem item = DynamoDbItem.create(projection.key(), itemType, payloadJson, now);
        item.setVersion(version);
        item.setCreatedAt(canonical.getCreatedAt());
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

    private static DynamoDbTableGateway.WriteCondition condition(
            String expression,
            Map<String, String> names,
            Map<String, AttributeValue> values
    ) {
        return DynamoDbTableGateway.WriteCondition.of(expression, names, values);
    }

    private static List<DynamoProjection> distinct(Collection<DynamoProjection> projections) {
        return projections == null || projections.isEmpty()
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(projections));
    }

    private static Set<DynamoDbKey> keys(Collection<DynamoProjection> projections) {
        LinkedHashSet<DynamoDbKey> result = new LinkedHashSet<>();
        projections.forEach(projection -> result.add(projection.key()));
        return Set.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class ChallengeWriteConflictException extends DataIntegrityViolationException {
        public ChallengeWriteConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
