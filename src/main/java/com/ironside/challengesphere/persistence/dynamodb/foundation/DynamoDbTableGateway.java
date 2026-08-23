package com.ironside.challengesphere.persistence.dynamodb.foundation;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Restricted low-level gateway for one DynamoDB table. It exposes only keyed
 * operations and deliberately has no whole-table read operation.
 */
public class DynamoDbTableGateway {

    private static final int MAX_BATCH_GET_ITEMS = 100;
    private static final int MAX_BATCH_WRITE_ITEMS = 25;
    private static final int MAX_TRANSACTION_ITEMS = 100;

    private final DynamoDbClient client;
    private final TableSchema<DynamoDbItem> itemSchema;
    private final String tableName;

    protected DynamoDbTableGateway(
            DynamoDbClient client,
            TableSchema<DynamoDbItem> itemSchema,
            String tableName
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.itemSchema = Objects.requireNonNull(itemSchema, "itemSchema");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }

    public Optional<DynamoDbItem> get(DynamoDbKey key, boolean consistentRead) {
        Objects.requireNonNull(key, "key");
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key.toAttributeMap())
                .consistentRead(consistentRead)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toItem(response.item()));
    }

    /**
     * Executes a caller-built key query against this gateway's table. The
     * caller may provide index, expressions, paging key, projection, and limit;
     * table name and consistency are controlled by the gateway.
     */
    public QueryPage query(QueryRequest request, boolean consistentRead) {
        Objects.requireNonNull(request, "request");
        requireMatchingTable(request.tableName());
        if (consistentRead && request.indexName() != null && !request.indexName().isBlank()) {
            throw new IllegalArgumentException("DynamoDB global secondary indexes do not support strong reads");
        }
        QueryResponse response = client.query(request.toBuilder()
                .tableName(tableName)
                .consistentRead(consistentRead)
                .build());
        List<DynamoDbItem> items = response.items().stream().map(this::toItem).toList();
        return new QueryPage(
                items,
                response.lastEvaluatedKey(),
                response.count(),
                response.consumedCapacity()
        );
    }

    public PutItemResponse put(DynamoDbItem item) {
        return put(item, WriteCondition.none());
    }

    public PutItemResponse put(DynamoDbItem item, WriteCondition condition) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(condition, "condition");
        PutItemRequest.Builder request = PutItemRequest.builder()
                .tableName(tableName)
                .item(toAttributeMap(item));
        condition.apply(request);
        return client.putItem(request.build());
    }

    /**
     * Executes an atomic update request while pinning it to this gateway's
     * table. Update and condition expressions remain domain-owned.
     */
    public UpdateItemResponse update(UpdateItemRequest request) {
        Objects.requireNonNull(request, "request");
        requireMatchingTable(request.tableName());
        requireKey(request.key());
        return client.updateItem(request.toBuilder().tableName(tableName).build());
    }

    public DeleteItemResponse delete(DynamoDbKey key) {
        return delete(key, WriteCondition.none());
    }

    public DeleteItemResponse delete(DynamoDbKey key, WriteCondition condition) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(condition, "condition");
        DeleteItemRequest.Builder request = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key.toAttributeMap());
        condition.apply(request);
        return client.deleteItem(request.build());
    }

    /** Returns unprocessed keys to the caller for bounded, jittered retry. */
    public BatchGetResult batchGet(Collection<DynamoDbKey> requestedKeys, boolean consistentRead) {
        List<DynamoDbKey> keys = List.copyOf(Objects.requireNonNull(requestedKeys, "requestedKeys"));
        requireSize("batch get", keys.size(), MAX_BATCH_GET_ITEMS);
        if (keys.isEmpty()) {
            return new BatchGetResult(List.of(), List.of());
        }

        KeysAndAttributes keysAndAttributes = DynamoDbKeys.keysAndAttributes(keys, consistentRead);
        BatchGetItemResponse response = client.batchGetItem(BatchGetItemRequest.builder()
                .requestItems(Map.of(tableName, keysAndAttributes))
                .build());
        List<DynamoDbItem> items = response.responses()
                .getOrDefault(tableName, List.of())
                .stream()
                .map(this::toItem)
                .toList();
        List<DynamoDbKey> unprocessed = Optional.ofNullable(response.unprocessedKeys().get(tableName))
                .map(KeysAndAttributes::keys)
                .orElseGet(List::of)
                .stream()
                .map(DynamoDbTableGateway::toKey)
                .toList();
        return new BatchGetResult(items, unprocessed);
    }

    /** Returns unprocessed writes to the caller for bounded, jittered retry. */
    public BatchWriteResult batchWrite(
            Collection<DynamoDbItem> itemsToPut,
            Collection<DynamoDbKey> keysToDelete
    ) {
        List<DynamoDbItem> puts = List.copyOf(Objects.requireNonNull(itemsToPut, "itemsToPut"));
        List<DynamoDbKey> deletes = List.copyOf(Objects.requireNonNull(keysToDelete, "keysToDelete"));
        requireSize("batch write", puts.size() + deletes.size(), MAX_BATCH_WRITE_ITEMS);
        if (puts.isEmpty() && deletes.isEmpty()) {
            return new BatchWriteResult(List.of());
        }

        List<WriteRequest> writes = new ArrayList<>(puts.size() + deletes.size());
        puts.forEach(item -> writes.add(WriteRequest.builder()
                .putRequest(PutRequest.builder().item(toAttributeMap(item)).build())
                .build()));
        deletes.forEach(key -> writes.add(WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder().key(key.toAttributeMap()).build())
                .build()));

        BatchWriteItemResponse response = client.batchWriteItem(BatchWriteItemRequest.builder()
                .requestItems(Map.of(tableName, writes))
                .build());
        return new BatchWriteResult(
                response.unprocessedItems().getOrDefault(tableName, List.of())
        );
    }

    public TransactWriteItemsResponse transactWrite(
            Collection<TransactWriteItem> requestedActions,
            String clientRequestToken
    ) {
        List<TransactWriteItem> actions = List.copyOf(
                Objects.requireNonNull(requestedActions, "requestedActions")
        );
        requireSize("transaction", actions.size(), MAX_TRANSACTION_ITEMS);
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("transaction must contain at least one action");
        }
        actions.forEach(this::requireTransactionTargetsThisTable);

        TransactWriteItemsRequest.Builder request = TransactWriteItemsRequest.builder()
                .transactItems(actions);
        if (clientRequestToken != null && !clientRequestToken.isBlank()) {
            request.clientRequestToken(clientRequestToken);
        }
        return client.transactWriteItems(request.build());
    }

    public TransactGetItemsResponse transactGet(Collection<TransactGetItem> requestedItems) {
        List<TransactGetItem> items = List.copyOf(Objects.requireNonNull(requestedItems, "requestedItems"));
        requireSize("transaction read", items.size(), MAX_TRANSACTION_ITEMS);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("transaction read must contain at least one item");
        }
        items.forEach(item -> requireMatchingTable(item.get().tableName()));
        return client.transactGetItems(TransactGetItemsRequest.builder().transactItems(items).build());
    }

    public TransactWriteItem transactionPut(DynamoDbItem item, WriteCondition condition) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(condition, "condition");
        Put.Builder put = Put.builder().tableName(tableName).item(toAttributeMap(item));
        condition.apply(put);
        return TransactWriteItem.builder().put(put.build()).build();
    }

    public TransactWriteItem transactionUpdate(
            DynamoDbKey key,
            String updateExpression,
            WriteCondition condition,
            Map<String, String> expressionAttributeNames,
            Map<String, AttributeValue> expressionAttributeValues
    ) {
        Objects.requireNonNull(key, "key");
        if (updateExpression == null || updateExpression.isBlank()) {
            throw new IllegalArgumentException("updateExpression must not be blank");
        }
        Update.Builder update = Update.builder()
                .tableName(tableName)
                .key(key.toAttributeMap())
                .updateExpression(updateExpression);
        applyExpressionMaps(update, expressionAttributeNames, expressionAttributeValues);
        Objects.requireNonNull(condition, "condition").apply(update);
        return TransactWriteItem.builder().update(update.build()).build();
    }

    public TransactWriteItem transactionDelete(DynamoDbKey key, WriteCondition condition) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(condition, "condition");
        Delete.Builder delete = Delete.builder().tableName(tableName).key(key.toAttributeMap());
        condition.apply(delete);
        return TransactWriteItem.builder().delete(delete.build()).build();
    }

    public TransactWriteItem transactionConditionCheck(DynamoDbKey key, WriteCondition condition) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(condition, "condition");
        if (!condition.isPresent()) {
            throw new IllegalArgumentException("condition check requires a condition expression");
        }
        ConditionCheck.Builder check = ConditionCheck.builder()
                .tableName(tableName)
                .key(key.toAttributeMap());
        condition.apply(check);
        return TransactWriteItem.builder().conditionCheck(check.build()).build();
    }

    public TransactGetItem transactionGet(DynamoDbKey key) {
        Objects.requireNonNull(key, "key");
        return TransactGetItem.builder()
                .get(Get.builder().tableName(tableName).key(key.toAttributeMap()).build())
                .build();
    }

    public Map<String, AttributeValue> toAttributeMap(DynamoDbItem item) {
        return Map.copyOf(itemSchema.itemToMap(Objects.requireNonNull(item, "item"), true));
    }

    public DynamoDbItem toItem(Map<String, AttributeValue> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("attributes must not be empty");
        }
        return itemSchema.mapToItem(attributes);
    }

    private void requireTransactionTargetsThisTable(TransactWriteItem action) {
        Objects.requireNonNull(action, "transaction action");
        int selected = 0;
        if (action.put() != null) {
            requireMatchingTable(action.put().tableName());
            selected++;
        }
        if (action.update() != null) {
            requireMatchingTable(action.update().tableName());
            selected++;
        }
        if (action.delete() != null) {
            requireMatchingTable(action.delete().tableName());
            selected++;
        }
        if (action.conditionCheck() != null) {
            requireMatchingTable(action.conditionCheck().tableName());
            selected++;
        }
        if (selected != 1) {
            throw new IllegalArgumentException("each transaction action must select exactly one operation");
        }
    }

    private void requireMatchingTable(String requestedTable) {
        if (requestedTable != null && !requestedTable.isBlank() && !tableName.equals(requestedTable)) {
            throw new IllegalArgumentException("request targets a different DynamoDB table");
        }
    }

    private static void requireKey(Map<String, AttributeValue> key) {
        if (key == null || !key.containsKey(DynamoDbKeys.PARTITION_KEY)
                || !key.containsKey(DynamoDbKeys.SORT_KEY)) {
            throw new IllegalArgumentException("request must contain PK and SK");
        }
    }

    private static DynamoDbKey toKey(Map<String, AttributeValue> attributes) {
        requireKey(attributes);
        AttributeValue partitionKey = attributes.get(DynamoDbKeys.PARTITION_KEY);
        AttributeValue sortKey = attributes.get(DynamoDbKeys.SORT_KEY);
        if (partitionKey.s() == null || sortKey.s() == null) {
            throw new IllegalArgumentException("PK and SK must be strings");
        }
        return new DynamoDbKey(partitionKey.s(), sortKey.s());
    }

    private static void requireSize(String operation, int size, int maximum) {
        if (size > maximum) {
            throw new IllegalArgumentException(operation + " supports at most " + maximum + " items");
        }
    }

    private static void applyExpressionMaps(
            Update.Builder update,
            Map<String, String> names,
            Map<String, AttributeValue> values
    ) {
        if (names != null && !names.isEmpty()) {
            update.expressionAttributeNames(names);
        }
        if (values != null && !values.isEmpty()) {
            update.expressionAttributeValues(values);
        }
    }

    public record QueryPage(
            List<DynamoDbItem> items,
            Map<String, AttributeValue> lastEvaluatedKey,
            int count,
            ConsumedCapacity consumedCapacity
    ) {
        public QueryPage {
            items = List.copyOf(items);
            lastEvaluatedKey = lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()
                    ? Map.of()
                    : Map.copyOf(lastEvaluatedKey);
        }
    }

    public record BatchGetResult(List<DynamoDbItem> items, List<DynamoDbKey> unprocessedKeys) {
        public BatchGetResult {
            items = List.copyOf(items);
            unprocessedKeys = List.copyOf(unprocessedKeys);
        }
    }

    public record BatchWriteResult(List<WriteRequest> unprocessedWrites) {
        public BatchWriteResult {
            unprocessedWrites = List.copyOf(unprocessedWrites);
        }
    }

    public record WriteCondition(
            String expression,
            Map<String, String> expressionAttributeNames,
            Map<String, AttributeValue> expressionAttributeValues
    ) {
        public WriteCondition {
            expressionAttributeNames = immutable(expressionAttributeNames);
            expressionAttributeValues = immutable(expressionAttributeValues);
        }

        public static WriteCondition none() {
            return new WriteCondition(null, Map.of(), Map.of());
        }

        public static WriteCondition of(
                String expression,
                Map<String, String> expressionAttributeNames,
                Map<String, AttributeValue> expressionAttributeValues
        ) {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("condition expression must not be blank");
            }
            return new WriteCondition(expression, expressionAttributeNames, expressionAttributeValues);
        }

        private boolean isPresent() {
            return expression != null && !expression.isBlank();
        }

        private void apply(PutItemRequest.Builder builder) {
            if (isPresent()) {
                builder.conditionExpression(expression);
                applyMaps(builder::expressionAttributeNames, builder::expressionAttributeValues);
            }
        }

        private void apply(DeleteItemRequest.Builder builder) {
            if (isPresent()) {
                builder.conditionExpression(expression);
                applyMaps(builder::expressionAttributeNames, builder::expressionAttributeValues);
            }
        }

        private void apply(Put.Builder builder) {
            if (isPresent()) {
                builder.conditionExpression(expression);
                applyMaps(builder::expressionAttributeNames, builder::expressionAttributeValues);
            }
        }

        private void apply(Update.Builder builder) {
            if (isPresent()) {
                builder.conditionExpression(expression);
                // An Update may already have expression maps for its SET/ADD
                // expression. Merge before applying to avoid overwriting them.
                Map<String, String> names = new LinkedHashMap<>(builder.build().expressionAttributeNames());
                names.putAll(expressionAttributeNames);
                Map<String, AttributeValue> values = new LinkedHashMap<>(builder.build().expressionAttributeValues());
                values.putAll(expressionAttributeValues);
                if (!names.isEmpty()) {
                    builder.expressionAttributeNames(names);
                }
                if (!values.isEmpty()) {
                    builder.expressionAttributeValues(values);
                }
            }
        }

        private void apply(Delete.Builder builder) {
            if (isPresent()) {
                builder.conditionExpression(expression);
                applyMaps(builder::expressionAttributeNames, builder::expressionAttributeValues);
            }
        }

        private void apply(ConditionCheck.Builder builder) {
            builder.conditionExpression(expression);
            applyMaps(builder::expressionAttributeNames, builder::expressionAttributeValues);
        }

        private void applyMaps(
                java.util.function.Consumer<Map<String, String>> namesConsumer,
                java.util.function.Consumer<Map<String, AttributeValue>> valuesConsumer
        ) {
            if (!expressionAttributeNames.isEmpty()) {
                namesConsumer.accept(expressionAttributeNames);
            }
            if (!expressionAttributeValues.isEmpty()) {
                valuesConsumer.accept(expressionAttributeValues);
            }
        }

        private static <K, V> Map<K, V> immutable(Map<K, V> values) {
            return values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
        }
    }
}
