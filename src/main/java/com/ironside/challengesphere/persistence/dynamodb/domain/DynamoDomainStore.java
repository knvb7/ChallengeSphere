package com.ironside.challengesphere.persistence.dynamodb.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.UlidGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway.WriteCondition;

/**
 * JSON aggregate store shared by the domain-specific DynamoDB repositories.
 *
 * <p>The canonical item and all primary-key materializations are changed in a
 * single optimistic transaction. The class intentionally exposes no scan
 * operation.</p>
 */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class DynamoDomainStore {

    private static final int MAX_ITEM_BYTES = 350 * 1024;
    private static final int MAX_QUERY_LIMIT = 100;
    private static final int MAX_TRANSACTION_ITEMS = 100;
    private static final String PROJECTION_ENTITY_PREFIX = "PROJECTION#";

    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;

    public DynamoDomainStore(
            MainTableGateway gateway,
            ObjectMapper objectMapper,
            UlidGenerator ulidGenerator,
            Clock clock
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ulidGenerator = Objects.requireNonNull(ulidGenerator, "ulidGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String nextId() {
        return ulidGenerator.next();
    }

    public DynamoDbKey canonicalKey(String entityType, String id) {
        return DynamoDbKeys.key(DynamoAccessPatterns.entityPk(entityType, id), DynamoAccessPatterns.META);
    }

    public <T> Optional<T> get(
            String entityType,
            String id,
            Class<T> payloadType,
            boolean consistentRead
    ) {
        return gateway.get(canonicalKey(entityType, id), consistentRead)
                .map(item -> decode(item, payloadType));
    }

    public <T> Optional<Versioned<T>> loadVersioned(
            String entityType,
            String id,
            Class<T> payloadType
    ) {
        return gateway.get(canonicalKey(entityType, id), true)
                .map(item -> new Versioned<>(
                        decode(item, payloadType),
                        Optional.ofNullable(item.getVersion()).orElse(0L)
                ));
    }

    public <T> List<T> batchGet(
            String entityType,
            Collection<String> ids,
            Class<T> payloadType,
            boolean consistentRead
    ) {
        List<DynamoDbKey> keys = ids.stream().distinct()
                .map(id -> canonicalKey(entityType, id))
                .toList();
        List<T> result = new ArrayList<>(keys.size());
        for (int offset = 0; offset < keys.size(); offset += 100) {
            List<DynamoDbKey> pending = new ArrayList<>(keys.subList(offset, Math.min(keys.size(), offset + 100)));
            int attempts = 0;
            while (!pending.isEmpty()) {
                if (++attempts > 5) {
                    throw new IllegalStateException("DynamoDB batch get remained unprocessed after 5 attempts");
                }
                DynamoDbTableGateway.BatchGetResult page = gateway.batchGet(pending, consistentRead);
                page.items().stream().map(item -> decode(item, payloadType)).forEach(result::add);
                pending = new ArrayList<>(page.unprocessedKeys());
                if (!pending.isEmpty()) {
                    backoff(attempts);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Batch-hydrates known projection keys without falling back to a collection query.
     * Callers use this for fixed-key relationship and membership authorization checks.
     */
    public <T> List<T> batchGetProjections(
            Collection<DynamoDbKey> requestedKeys,
            Class<T> payloadType,
            boolean consistentRead
    ) {
        Objects.requireNonNull(requestedKeys, "requestedKeys");
        Objects.requireNonNull(payloadType, "payloadType");
        List<DynamoDbKey> keys = requestedKeys.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<T> result = new ArrayList<>(keys.size());
        for (int offset = 0; offset < keys.size(); offset += 100) {
            List<DynamoDbKey> pending = new ArrayList<>(
                    keys.subList(offset, Math.min(keys.size(), offset + 100))
            );
            int attempts = 0;
            while (!pending.isEmpty()) {
                if (++attempts > 5) {
                    throw new IllegalStateException(
                            "DynamoDB projection batch get remained unprocessed after 5 attempts"
                    );
                }
                DynamoDbTableGateway.BatchGetResult page = gateway.batchGet(pending, consistentRead);
                page.items().stream().map(item -> decode(item, payloadType)).forEach(result::add);
                pending = new ArrayList<>(page.unprocessedKeys());
                if (!pending.isEmpty()) {
                    backoff(attempts);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void backoff(int attempt) {
        long ceilingMillis = Math.min(250L, 5L << Math.min(Math.max(0, attempt - 1), 6));
        long delayMillis = ThreadLocalRandom.current().nextLong(ceilingMillis + 1L);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a DynamoDB batch read", interrupted);
        }
    }

    public <T> DynamoPage<T> query(
            String partitionKey,
            String sortKeyPrefix,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey,
            boolean ascending,
            Class<T> payloadType,
            boolean consistentRead
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#pk", DynamoDbKeys.PARTITION_KEY);
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":pk", AttributeValue.fromS(requireText(partitionKey, "partitionKey")));

        String keyCondition = "#pk = :pk";
        if (sortKeyPrefix != null && !sortKeyPrefix.isBlank()) {
            names.put("#sk", DynamoDbKeys.SORT_KEY);
            values.put(":skPrefix", AttributeValue.fromS(sortKeyPrefix));
            keyCondition += " AND begins_with(#sk, :skPrefix)";
        }

        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(gateway.tableName())
                .keyConditionExpression(keyCondition)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .limit(boundedLimit)
                .scanIndexForward(ascending);
        if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
            request.exclusiveStartKey(exclusiveStartKey);
        }

        DynamoDbTableGateway.QueryPage page = gateway.query(request.build(), consistentRead);
        return new DynamoPage<>(
                page.items().stream().map(item -> decode(item, payloadType)).toList(),
                page.lastEvaluatedKey()
        );
    }

    /**
     * Reads a deliberately bounded collection partition. Repository methods
     * that previously returned an unbounded SQL list must supply an explicit
     * ceiling instead of silently scanning the table.
     */
    public <T> List<T> queryAll(
            String partitionKey,
            String sortKeyPrefix,
            int maximumItems,
            boolean ascending,
            Class<T> payloadType,
            boolean consistentRead
    ) {
        if (maximumItems < 1) {
            throw new IllegalArgumentException("maximumItems must be positive");
        }
        List<T> result = new ArrayList<>(Math.min(maximumItems, MAX_QUERY_LIMIT));
        Map<String, AttributeValue> cursor = Map.of();
        do {
            DynamoPage<T> page = query(
                    partitionKey,
                    sortKeyPrefix,
                    Math.min(MAX_QUERY_LIMIT, maximumItems - result.size()),
                    cursor,
                    ascending,
                    payloadType,
                    consistentRead
            );
            result.addAll(page.items());
            cursor = page.lastEvaluatedKey();
        } while (!cursor.isEmpty() && result.size() < maximumItems);
        return List.copyOf(result);
    }

    /**
     * Creates or optimistically replaces one aggregate and all of its access
     * projections. Callers pass the old projections when indexed attributes
     * changed so stale keys are removed atomically.
     */
    public <T> long save(
            String entityType,
            String id,
            T payload,
            Collection<DynamoProjection> newProjections,
            Collection<DynamoProjection> previousProjections,
            boolean createOnly
    ) {
        return save(
                entityType,
                id,
                payload,
                newProjections,
                previousProjections,
                createOnly,
                null
        );
    }

    public <T> long save(
            String entityType,
            String id,
            T payload,
            Collection<DynamoProjection> newProjections,
            Collection<DynamoProjection> previousProjections,
            boolean createOnly,
            Long expectedVersion
    ) {
        Objects.requireNonNull(payload, "payload");
        String normalizedType = requireText(entityType, "entityType").toUpperCase();
        DynamoDbKey canonicalKey = canonicalKey(normalizedType, id);
        Optional<DynamoDbItem> previous = gateway.get(canonicalKey, true);

        if (createOnly && previous.isPresent()) {
            throw new DuplicateAggregateException(normalizedType, id);
        }

        long previousVersion = previous.map(DynamoDbItem::getVersion).orElse(0L);
        if (expectedVersion != null
                && (previous.isEmpty() || previousVersion != expectedVersion)) {
            throw new OptimisticWriteException(normalizedType, id, null);
        }
        long nextVersion = previousVersion + 1;
        Instant now = clock.instant();
        String payloadJson = encode(payload);

        DynamoDbItem canonical = DynamoDbItem.create(canonicalKey, normalizedType, payloadJson, now);
        canonical.setVersion(nextVersion);
        previous.map(DynamoDbItem::getCreatedAt).ifPresent(canonical::setCreatedAt);

        List<DynamoProjection> newProjectionList = distinct(newProjections);
        Set<DynamoDbKey> retainedKeys = newProjectionList.stream()
                .map(DynamoProjection::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<DynamoProjection> staleProjections = distinct(previousProjections).stream()
                .filter(projection -> !retainedKeys.contains(projection.key()))
                .toList();

        int actionCount = 1 + newProjectionList.size() + staleProjections.size();
        if (actionCount > MAX_TRANSACTION_ITEMS) {
            throw new IllegalArgumentException(
                    "aggregate write requires " + actionCount + " actions; asynchronous fan-out is required"
            );
        }

        List<TransactWriteItem> actions = new ArrayList<>(actionCount);
        WriteCondition canonicalCondition = previous.isEmpty()
                ? WriteCondition.of("attribute_not_exists(#pk)", Map.of("#pk", DynamoDbKeys.PARTITION_KEY), Map.of())
                : WriteCondition.of(
                        "#version = :expectedVersion",
                        Map.of("#version", DynamoDbKeys.VERSION),
                        Map.of(":expectedVersion", AttributeValue.fromN(Long.toString(previousVersion)))
                );
        actions.add(gateway.transactionPut(canonical, canonicalCondition));

        for (DynamoProjection projection : newProjectionList) {
            DynamoDbItem projectionItem = DynamoDbItem.create(
                    projection.key(),
                    PROJECTION_ENTITY_PREFIX + normalizedType,
                    payloadJson,
                    now
            );
            projectionItem.setVersion(nextVersion);
            projectionItem.setCreatedAt(canonical.getCreatedAt());
            actions.add(gateway.transactionPut(projectionItem, WriteCondition.none()));
        }
        staleProjections.forEach(projection -> actions.add(
                gateway.transactionDelete(projection.key(), WriteCondition.none())
        ));

        try {
            gateway.transactWrite(actions, ulidGenerator.next());
        } catch (ConditionalCheckFailedException conflict) {
            throw new OptimisticWriteException(normalizedType, id, conflict);
        } catch (software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException conflict) {
            throw new OptimisticWriteException(normalizedType, id, conflict);
        }
        return nextVersion;
    }

    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections
    ) {
        Optional<DynamoDbItem> current = gateway.get(canonicalKey(entityType, id), true);
        if (current.isEmpty()) {
            return;
        }
        delete(entityType, id, projections, current.get().getVersion());
    }

    public void delete(
            String entityType,
            String id,
            Collection<DynamoProjection> projections,
            long expectedVersion
    ) {
        List<DynamoProjection> projectionList = distinct(projections);
        if (projectionList.size() + 1 > MAX_TRANSACTION_ITEMS) {
            throw new IllegalArgumentException("aggregate deletion must be processed asynchronously");
        }
        List<TransactWriteItem> actions = new ArrayList<>(projectionList.size() + 1);
        actions.add(gateway.transactionDelete(
                canonicalKey(entityType, id),
                WriteCondition.of(
                        "#version = :expectedVersion",
                        Map.of("#version", DynamoDbKeys.VERSION),
                        Map.of(":expectedVersion", AttributeValue.fromN(Long.toString(expectedVersion)))
                )
        ));
        projectionList.forEach(projection -> actions.add(
                gateway.transactionDelete(projection.key(), WriteCondition.none())
        ));
        try {
            gateway.transactWrite(actions, ulidGenerator.next());
        } catch (ConditionalCheckFailedException
                 | software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException conflict) {
            throw new OptimisticWriteException(entityType, id, conflict);
        }
    }

    public MainTableGateway gateway() {
        return gateway;
    }

    private <T> T decode(DynamoDbItem item, Class<T> payloadType) {
        try {
            T payload = objectMapper.readValue(item.getPayloadJson(), payloadType);
            if (payload instanceof DynamoVersionedAggregate versioned) {
                versioned.setDynamoVersion(Optional.ofNullable(item.getVersion()).orElse(0L));
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to decode " + item.getEntityType() + " at " + item.getPk() + '/' + item.getSk(),
                    exception
            );
        }
    }

    private String encode(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ITEM_BYTES) {
                throw new IllegalArgumentException("DynamoDB aggregate payload exceeds the 350 KiB guardrail");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode DynamoDB aggregate payload", exception);
        }
    }

    private static List<DynamoProjection> distinct(Collection<DynamoProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new HashSet<>(projections));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class DuplicateAggregateException extends RuntimeException {
        public DuplicateAggregateException(String entityType, String id) {
            super(entityType + " " + id + " already exists");
        }
    }

    public static final class OptimisticWriteException extends RuntimeException {
        public OptimisticWriteException(String entityType, String id, Throwable cause) {
            super("Concurrent update rejected for " + entityType + ' ' + id, cause);
        }
    }

    public record Versioned<T>(T value, long version) {
    }
}
