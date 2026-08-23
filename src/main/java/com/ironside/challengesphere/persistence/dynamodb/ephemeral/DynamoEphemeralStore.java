package com.ironside.challengesphere.persistence.dynamodb.ephemeral;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB implementation of the security and short-lived state store.
 *
 * <p>Every item uses a namespaced partition key. The {@code expiresAt}
 * attribute is the table TTL value in epoch seconds; authorization conditions
 * use {@code expiresAtEpochMillis} so sub-second truncation never changes the
 * effective lifetime.</p>
 */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class DynamoEphemeralStore implements EphemeralStore {

    static final String PARTITION_KEY = "PK";
    static final String SORT_KEY = "SK";
    static final String EXPIRES_AT = "expiresAt";
    static final String EXPIRES_AT_MILLIS = "expiresAtEpochMillis";

    private static final String KIND = "kind";
    private static final String STATE_SORT_KEY = "STATE";
    private static final String USER_ID = "userId";
    private static final String TOKEN_TYPE = "tokenType";
    private static final String TOKEN_HASH = "tokenHash";
    private static final String TARGET_PK = "targetPK";
    private static final String TARGET_SK = "targetSK";
    private static final String PURPOSE = "purpose";
    private static final String OBJECT_KEY = "objectKey";
    private static final String MEMBER = "member";
    private static final String COUNT = "count";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoEphemeralStore(
            DynamoDbClient dynamoDbClient,
            @Value("${app.dynamodb.ephemeral-table}") String tableName
    ) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException("DynamoDB ephemeral table name must not be blank");
        }
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public void putAuthToken(String tokenHash, String tokenType, String userId, Instant expiresAt) {
        requireText(tokenHash, "Token hash");
        requireText(tokenType, "Token type");
        requireText(userId, "User ID");
        requireFutureExpiry(expiresAt, Instant.now());

        String tokenPk = authTokenPk(tokenHash, tokenType);
        String userPk = authUserPk(userId);
        String indexSk = authIndexSk(tokenHash, tokenType);
        long nowMillis = Instant.now().toEpochMilli();

        Map<String, AttributeValue> token = expiringItem(tokenPk, STATE_SORT_KEY, "AUTH_TOKEN", expiresAt);
        token.put(TOKEN_HASH, stringValue(tokenHash));
        token.put(TOKEN_TYPE, stringValue(tokenType));
        token.put(USER_ID, stringValue(userId));

        Map<String, AttributeValue> userIndex = expiringItem(userPk, indexSk, "AUTH_TOKEN_OWNER", expiresAt);
        userIndex.put(TOKEN_HASH, stringValue(tokenHash));
        userIndex.put(TOKEN_TYPE, stringValue(tokenType));
        userIndex.put(USER_ID, stringValue(userId));
        userIndex.put(TARGET_PK, stringValue(tokenPk));
        userIndex.put(TARGET_SK, stringValue(STATE_SORT_KEY));

        Put tokenPut = Put.builder()
                .tableName(tableName)
                .item(token)
                .conditionExpression("attribute_not_exists(#pk) OR #expiryMillis <= :now OR #userId = :userId")
                .expressionAttributeNames(Map.of(
                        "#pk", PARTITION_KEY,
                        "#expiryMillis", EXPIRES_AT_MILLIS,
                        "#userId", USER_ID
                ))
                .expressionAttributeValues(Map.of(
                        ":now", numberValue(nowMillis),
                        ":userId", stringValue(userId)
                ))
                .build();

        Put indexPut = Put.builder()
                .tableName(tableName)
                .item(userIndex)
                .build();

        dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(tokenPut).build(),
                        TransactWriteItem.builder().put(indexPut).build()
                )
                .build());
    }

    @Override
    public boolean authTokenExists(String tokenHash, String tokenType, String userId, Instant now) {
        Optional<AuthTokenRecord> record = readAuthToken(tokenHash, tokenType);
        return record.filter(value -> value.userId().equals(userId))
                .filter(value -> value.expiresAtEpochMillis() > now.toEpochMilli())
                .isPresent();
    }

    @Override
    public Optional<String> findAuthTokenUser(
            String tokenHash,
            String tokenType,
            Instant now
    ) {
        requireText(tokenHash, "Token hash");
        requireText(tokenType, "Token type");
        return readAuthToken(tokenHash, tokenType)
                .filter(value -> value.expiresAtEpochMillis() > now.toEpochMilli())
                .map(AuthTokenRecord::userId);
    }

    @Override
    public Optional<String> consumeAuthToken(String tokenHash, String tokenType, Instant now) {
        Optional<AuthTokenRecord> result = readAuthToken(tokenHash, tokenType);
        if (result.isEmpty() || result.get().expiresAtEpochMillis() <= now.toEpochMilli()) {
            return Optional.empty();
        }

        AuthTokenRecord record = result.get();
        Delete tokenDelete = Delete.builder()
                .tableName(tableName)
                .key(key(record.tokenPk(), STATE_SORT_KEY))
                .conditionExpression("#userId = :userId AND #expiryMillis > :now")
                .expressionAttributeNames(Map.of(
                        "#userId", USER_ID,
                        "#expiryMillis", EXPIRES_AT_MILLIS
                ))
                .expressionAttributeValues(Map.of(
                        ":userId", stringValue(record.userId()),
                        ":now", numberValue(now.toEpochMilli())
                ))
                .build();

        Delete indexDelete = Delete.builder()
                .tableName(tableName)
                .key(key(authUserPk(record.userId()), authIndexSk(tokenHash, tokenType)))
                .build();

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().delete(tokenDelete).build(),
                            TransactWriteItem.builder().delete(indexDelete).build()
                    )
                    .build());
            return Optional.of(record.userId());
        } catch (TransactionCanceledException alreadyConsumedOrExpired) {
            return Optional.empty();
        }
    }

    @Override
    public void revokeAuthToken(String tokenHash, String tokenType) {
        Optional<AuthTokenRecord> result = readAuthToken(tokenHash, tokenType);
        if (result.isEmpty()) {
            return;
        }

        AuthTokenRecord record = result.get();
        Delete tokenDelete = Delete.builder()
                .tableName(tableName)
                .key(key(record.tokenPk(), STATE_SORT_KEY))
                .conditionExpression("#userId = :userId")
                .expressionAttributeNames(Map.of("#userId", USER_ID))
                .expressionAttributeValues(Map.of(":userId", stringValue(record.userId())))
                .build();
        Delete indexDelete = Delete.builder()
                .tableName(tableName)
                .key(key(authUserPk(record.userId()), authIndexSk(tokenHash, tokenType)))
                .build();

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().delete(tokenDelete).build(),
                            TransactWriteItem.builder().delete(indexDelete).build()
                    )
                    .build());
        } catch (TransactionCanceledException concurrentlyChanged) {
            // Revocation is idempotent. A changed owner must not be deleted.
        }
    }

    @Override
    public void revokeAllAuthTokens(String userId) {
        revokeAllAuthTokensExcept(userId, null, null);
    }

    @Override
    public void revokeAllAuthTokensExcept(
            String userId,
            String preservedTokenHash,
            String preservedTokenType
    ) {
        requireText(userId, "User ID");
        boolean preserveToken = preservedTokenHash != null && !preservedTokenHash.isBlank()
                && preservedTokenType != null && !preservedTokenType.isBlank();
        Map<String, AttributeValue> startKey = Map.of();
        do {
            QueryRequest.Builder query = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("#pk = :pk")
                    .expressionAttributeNames(Map.of("#pk", PARTITION_KEY))
                    .expressionAttributeValues(Map.of(":pk", stringValue(authUserPk(userId))))
                    .consistentRead(true);
            if (!startKey.isEmpty()) {
                query.exclusiveStartKey(startKey);
            }

            var page = dynamoDbClient.query(query.build());
            for (Map<String, AttributeValue> indexItem : page.items()) {
                if (preserveToken
                        && attributeEquals(indexItem, TOKEN_HASH, preservedTokenHash)
                        && attributeEquals(indexItem, TOKEN_TYPE, preservedTokenType)) {
                    continue;
                }
                AttributeValue targetPk = indexItem.get(TARGET_PK);
                AttributeValue targetSk = indexItem.get(TARGET_SK);
                if (targetPk != null && targetPk.s() != null && targetSk != null && targetSk.s() != null) {
                    try {
                        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                                .tableName(tableName)
                                .key(key(targetPk.s(), targetSk.s()))
                                .conditionExpression("#userId = :userId")
                                .expressionAttributeNames(Map.of("#userId", USER_ID))
                                .expressionAttributeValues(Map.of(":userId", stringValue(userId)))
                                .build());
                    } catch (ConditionalCheckFailedException staleOwnerIndex) {
                        // A token collision was replaced for another owner; only remove this stale index.
                    }
                }
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                PARTITION_KEY, indexItem.get(PARTITION_KEY),
                                SORT_KEY, indexItem.get(SORT_KEY)
                        ))
                        .build());
            }
            startKey = page.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
    }

    private boolean attributeEquals(
            Map<String, AttributeValue> item,
            String attributeName,
            String expected
    ) {
        AttributeValue value = item.get(attributeName);
        return value != null && expected.equals(value.s());
    }

    @Override
    public RateCounterResult consumeRateLimit(
            String key,
            int maximum,
            Duration window,
            Instant now
    ) {
        requireText(key, "Rate-limit key");
        if (maximum < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit configuration is invalid");
        }

        long windowMillis;
        try {
            windowMillis = window.toMillis();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("Rate-limit window is too large", tooLarge);
        }
        if (windowMillis < 1) {
            throw new IllegalArgumentException("Rate-limit window must be at least one millisecond");
        }

        long nowMillis = now.toEpochMilli();
        long proposedExpiryMillis;
        try {
            proposedExpiryMillis = Math.addExact(nowMillis, windowMillis);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Rate-limit window is outside the supported range", overflow);
        }
        Instant proposedExpiry = Instant.ofEpochMilli(proposedExpiryMillis);
        String counterPk = namespacedKey("RATE_LIMIT", key);

        Map<String, String> names = Map.of(
                "#kind", KIND,
                "#count", COUNT,
                "#expiresAt", EXPIRES_AT,
                "#expiryMillis", EXPIRES_AT_MILLIS
        );
        Map<String, AttributeValue> resetValues = Map.of(
                ":kind", stringValue("RATE_LIMIT"),
                ":one", numberValue(1L),
                ":now", numberValue(nowMillis),
                ":expiresAt", numberValue(ttlEpochSeconds(proposedExpiry)),
                ":expiryMillis", numberValue(proposedExpiryMillis)
        );

        Map<String, AttributeValue> incrementValues = Map.of(
                ":one", numberValue(1L),
                ":maximum", numberValue(maximum),
                ":now", numberValue(nowMillis)
        );

        // A short retry handles the boundary race where another caller creates
        // or resets the counter between our two conditional updates.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                dynamoDbClient.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(key(counterPk, STATE_SORT_KEY))
                        .updateExpression("SET #kind = :kind, #count = :one, "
                                + "#expiresAt = :expiresAt, #expiryMillis = :expiryMillis")
                        .conditionExpression("attribute_not_exists(#count) "
                                + "OR attribute_not_exists(#expiryMillis) OR #expiryMillis <= :now")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(resetValues)
                        .build());
                return new RateCounterResult(
                        true,
                        maximum - 1,
                        Math.max(1L, ceilDiv(windowMillis, 1_000L))
                );
            } catch (ConditionalCheckFailedException activeWindow) {
                // The counter is active; consume below its maximum instead.
            }

            try {
                var response = dynamoDbClient.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(key(counterPk, STATE_SORT_KEY))
                        .updateExpression("ADD #count :one")
                        .conditionExpression("#expiryMillis > :now AND #count < :maximum")
                        .expressionAttributeNames(Map.of(
                                "#count", COUNT,
                                "#expiryMillis", EXPIRES_AT_MILLIS
                        ))
                        .expressionAttributeValues(incrementValues)
                        .returnValues(ReturnValue.ALL_NEW)
                        .build());
                long count = longAttribute(response.attributes(), COUNT).orElse(1L);
                long storedExpiry = longAttribute(response.attributes(), EXPIRES_AT_MILLIS)
                        .orElse(proposedExpiryMillis);
                return new RateCounterResult(
                        true,
                        (int) Math.max(0L, (long) maximum - count),
                        Math.max(1L, ceilDiv(Math.max(1L, storedExpiry - nowMillis), 1_000L))
                );
            } catch (ConditionalCheckFailedException reachedOrExpired) {
                Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(key(counterPk, STATE_SORT_KEY))
                                .consistentRead(true)
                                .build())
                        .item();
                if (item == null || item.isEmpty()) {
                    continue;
                }
                long storedExpiry = longAttribute(item, EXPIRES_AT_MILLIS).orElse(0L);
                if (storedExpiry <= nowMillis) {
                    continue;
                }
                long count = longAttribute(item, COUNT).orElse(0L);
                if (count >= maximum) {
                    return new RateCounterResult(
                            false,
                            0,
                            Math.max(1L, ceilDiv(storedExpiry - nowMillis, 1_000L))
                    );
                }
            }
        }

        // Under extreme contention, fail closed rather than allowing an
        // unrecorded request through.
        return new RateCounterResult(false, 0, Math.max(1L, ceilDiv(windowMillis, 1_000L)));
    }

    @Override
    public boolean reserveUploadQuota(
            String userId,
            LocalDate utcDate,
            long requestedObjects,
            long requestedBytes,
            long maximumObjects,
            long maximumBytes,
            Instant expiresAt,
            Instant now
    ) {
        requireText(userId, "User ID");
        if (utcDate == null || requestedObjects <= 0 || requestedBytes <= 0
                || maximumObjects <= 0 || maximumBytes <= 0) {
            throw new IllegalArgumentException("Upload quota configuration is invalid");
        }
        if (requestedObjects > maximumObjects || requestedBytes > maximumBytes) {
            return false;
        }
        requireFutureExpiry(expiresAt, now);

        String quotaPk = namespacedKey("UPLOAD_QUOTA", userId + "\u0000" + utcDate);
        Map<String, String> names = Map.of(
                "#kind", KIND,
                "#objects", "objects",
                "#bytes", "bytes",
                "#expiresAt", EXPIRES_AT,
                "#expiryMillis", EXPIRES_AT_MILLIS
        );
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":kind", stringValue("UPLOAD_QUOTA"));
        values.put(":requestedObjects", numberValue(requestedObjects));
        values.put(":requestedBytes", numberValue(requestedBytes));
        values.put(":objectCeiling", numberValue(maximumObjects - requestedObjects));
        values.put(":byteCeiling", numberValue(maximumBytes - requestedBytes));
        values.put(":expiresAt", numberValue(ttlEpochSeconds(expiresAt)));
        values.put(":expiryMillis", numberValue(expiresAt.toEpochMilli()));

        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(quotaPk, STATE_SORT_KEY))
                    .updateExpression("SET #kind = :kind, #expiresAt = :expiresAt, "
                            + "#expiryMillis = :expiryMillis "
                            + "ADD #objects :requestedObjects, #bytes :requestedBytes")
                    .conditionExpression("(attribute_not_exists(#objects) OR #objects <= :objectCeiling) "
                            + "AND (attribute_not_exists(#bytes) OR #bytes <= :byteCeiling)")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException quotaReached) {
            return false;
        }
    }

    @Override
    public boolean putGrant(
            String userId,
            String objectKey,
            String purpose,
            Instant expiresAt,
            Instant now
    ) {
        requireText(userId, "User ID");
        requireText(objectKey, "Object key");
        requireText(purpose, "Grant purpose");
        requireFutureExpiry(expiresAt, now);

        Map<String, AttributeValue> item = expiringItem(
                grantPk(userId, objectKey),
                STATE_SORT_KEY,
                "UPLOAD_GRANT",
                expiresAt
        );
        item.put(USER_ID, stringValue(userId));
        item.put(OBJECT_KEY, stringValue(objectKey));
        item.put(PURPOSE, stringValue(purpose));

        try {
            dynamoDbClient.putItem(request -> request
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(#pk) OR #expiryMillis <= :now")
                    .expressionAttributeNames(Map.of(
                            "#pk", PARTITION_KEY,
                            "#expiryMillis", EXPIRES_AT_MILLIS
                    ))
                    .expressionAttributeValues(Map.of(":now", numberValue(now.toEpochMilli()))));
            return true;
        } catch (ConditionalCheckFailedException alreadyIssued) {
            return false;
        }
    }

    @Override
    public Optional<Grant> getGrant(String userId, String objectKey, Instant now) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key(grantPk(userId, objectKey), STATE_SORT_KEY))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> expiry = longAttribute(item, EXPIRES_AT_MILLIS);
        String storedPurpose = stringAttribute(item, PURPOSE).orElse(null);
        String storedObjectKey = stringAttribute(item, OBJECT_KEY).orElse(null);
        if (expiry.isEmpty() || expiry.get() <= now.toEpochMilli()
                || storedPurpose == null || !objectKey.equals(storedObjectKey)) {
            return Optional.empty();
        }
        return Optional.of(new Grant(
                storedPurpose,
                storedObjectKey,
                Instant.ofEpochMilli(expiry.get())
        ));
    }

    @Override
    public boolean consumeGrants(String userId, List<String> objectKeys, String purpose, Instant now) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return true;
        }
        if (objectKeys.size() > 100) {
            throw new IllegalArgumentException("At most 100 grants can be consumed atomically");
        }

        List<TransactWriteItem> deletes = new ArrayList<>(objectKeys.size());
        for (String objectKey : objectKeys) {
            Delete delete = Delete.builder()
                    .tableName(tableName)
                    .key(key(grantPk(userId, objectKey), STATE_SORT_KEY))
                    .conditionExpression("#purpose = :purpose AND #objectKey = :objectKey "
                            + "AND #expiryMillis > :now")
                    .expressionAttributeNames(Map.of(
                            "#purpose", PURPOSE,
                            "#objectKey", OBJECT_KEY,
                            "#expiryMillis", EXPIRES_AT_MILLIS
                    ))
                    .expressionAttributeValues(Map.of(
                            ":purpose", stringValue(purpose),
                            ":objectKey", stringValue(objectKey),
                            ":now", numberValue(now.toEpochMilli())
                    ))
                    .build();
            deletes.add(TransactWriteItem.builder().delete(delete).build());
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(deletes)
                    .build());
            return true;
        } catch (TransactionCanceledException invalidOrConsumedGrant) {
            return false;
        }
    }

    @Override
    public void revokeGrant(String userId, String objectKey) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key(grantPk(userId, objectKey), STATE_SORT_KEY))
                .build());
    }

    @Override
    public boolean reserveNotificationQuota(
            String recipientUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            boolean deduplicated,
            int maximum,
            Instant expiresAt,
            Instant now
    ) {
        requireText(recipientUserId, "Recipient user ID");
        requireText(quotaKey, "Notification quota key");
        requireText(member, "Notification reservation member");
        if (maximum < 1) {
            throw new IllegalArgumentException("Notification maximum must be positive");
        }
        requireFutureExpiry(expiresAt, now);

        String quotaPk = namespacedKey("NOTIFICATION_QUOTA", quotaKey);
        String reservationPk = notificationReservationPk(recipientUserId, member);
        String dedupePk = null;
        if (deduplicated) {
            requireText(dedupeKey, "Notification dedupe key");
            dedupePk = namespacedKey("NOTIFICATION_DEDUPE", dedupeKey);
        }
        Map<String, AttributeValue> reservation = expiringItem(
                reservationPk,
                STATE_SORT_KEY,
                "NOTIFICATION_RESERVATION",
                expiresAt
        );
        reservation.put(USER_ID, stringValue(recipientUserId));
        reservation.put(MEMBER, stringValue(member));
        reservation.put("quotaPK", stringValue(quotaPk));
        reservation.put("deduplicated", AttributeValue.builder().bool(deduplicated).build());
        if (dedupePk != null) {
            reservation.put("dedupePK", stringValue(dedupePk));
        }

        List<TransactWriteItem> actions = new ArrayList<>(3);
        Update counterUpdate = Update.builder()
                .tableName(tableName)
                .key(key(quotaPk, STATE_SORT_KEY))
                .updateExpression("SET #kind = :kind, #expiresAt = :expiresAt, "
                        + "#expiryMillis = :expiryMillis ADD #count :one")
                .conditionExpression("(attribute_not_exists(#count) OR #count < :maximum) "
                        + "AND (attribute_not_exists(#expiryMillis) OR #expiryMillis > :now)")
                .expressionAttributeNames(Map.of(
                        "#kind", KIND,
                        "#count", COUNT,
                        "#expiresAt", EXPIRES_AT,
                        "#expiryMillis", EXPIRES_AT_MILLIS
                ))
                .expressionAttributeValues(Map.of(
                        ":kind", stringValue("NOTIFICATION_QUOTA"),
                        ":one", numberValue(1L),
                        ":maximum", numberValue(maximum),
                        ":expiresAt", numberValue(ttlEpochSeconds(expiresAt)),
                        ":expiryMillis", numberValue(expiresAt.toEpochMilli()),
                        ":now", numberValue(now.toEpochMilli())
                ))
                .build();
        actions.add(TransactWriteItem.builder().update(counterUpdate).build());

        Put reservationPut = Put.builder()
                .tableName(tableName)
                .item(reservation)
                .conditionExpression("attribute_not_exists(#pk) OR #expiryMillis <= :now")
                .expressionAttributeNames(Map.of(
                        "#pk", PARTITION_KEY,
                        "#expiryMillis", EXPIRES_AT_MILLIS
                ))
                .expressionAttributeValues(Map.of(":now", numberValue(now.toEpochMilli())))
                .build();
        actions.add(TransactWriteItem.builder().put(reservationPut).build());

        if (deduplicated) {
            Map<String, AttributeValue> dedupe = expiringItem(
                    dedupePk,
                    STATE_SORT_KEY,
                    "NOTIFICATION_DEDUPE",
                    expiresAt
            );
            dedupe.put(USER_ID, stringValue(recipientUserId));
            dedupe.put(MEMBER, stringValue(member));
            Put dedupePut = Put.builder()
                    .tableName(tableName)
                    .item(dedupe)
                    .conditionExpression("attribute_not_exists(#pk) OR #expiryMillis <= :now")
                    .expressionAttributeNames(Map.of(
                            "#pk", PARTITION_KEY,
                            "#expiryMillis", EXPIRES_AT_MILLIS
                    ))
                    .expressionAttributeValues(Map.of(":now", numberValue(now.toEpochMilli())))
                    .build();
            actions.add(TransactWriteItem.builder().put(dedupePut).build());
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(actions)
                    .build());
            return true;
        } catch (TransactionCanceledException quotaOrDedupeRejected) {
            return false;
        }
    }

    @Override
    public void releaseNotificationQuota(
            String recipientUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            boolean deduplicated,
            Instant now
    ) {
        String reservationPk = notificationReservationPk(recipientUserId, member);
        Map<String, AttributeValue> marker = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key(reservationPk, STATE_SORT_KEY))
                        .consistentRead(true)
                        .build())
                .item();
        if (marker == null || marker.isEmpty()
                || !member.equals(stringAttribute(marker, MEMBER).orElse(null))
                || longAttribute(marker, EXPIRES_AT_MILLIS).orElse(0L) <= now.toEpochMilli()) {
            return;
        }

        String quotaPk = namespacedKey("NOTIFICATION_QUOTA", quotaKey);
        if (!quotaPk.equals(stringAttribute(marker, "quotaPK").orElse(null))
                || booleanAttribute(marker, "deduplicated").orElse(!deduplicated) != deduplicated) {
            return;
        }
        String dedupePk = deduplicated
                ? namespacedKey("NOTIFICATION_DEDUPE", dedupeKey)
                : null;
        if (deduplicated && !dedupePk.equals(stringAttribute(marker, "dedupePK").orElse(null))) {
            return;
        }

        List<TransactWriteItem> actions = new ArrayList<>(3);
        Update counterUpdate = Update.builder()
                .tableName(tableName)
                .key(key(quotaPk, STATE_SORT_KEY))
                .updateExpression("ADD #count :minusOne")
                .conditionExpression("#count >= :one")
                .expressionAttributeNames(Map.of("#count", COUNT))
                .expressionAttributeValues(Map.of(
                        ":minusOne", numberValue(-1L),
                        ":one", numberValue(1L)
                ))
                .build();
        actions.add(TransactWriteItem.builder().update(counterUpdate).build());

        Delete markerDelete = Delete.builder()
                .tableName(tableName)
                .key(key(reservationPk, STATE_SORT_KEY))
                .conditionExpression("#member = :member AND #expiryMillis > :now")
                .expressionAttributeNames(Map.of(
                        "#member", MEMBER,
                        "#expiryMillis", EXPIRES_AT_MILLIS
                ))
                .expressionAttributeValues(Map.of(
                        ":member", stringValue(member),
                        ":now", numberValue(now.toEpochMilli())
                ))
                .build();
        actions.add(TransactWriteItem.builder().delete(markerDelete).build());

        if (deduplicated) {
            Delete dedupeDelete = Delete.builder()
                    .tableName(tableName)
                    .key(key(dedupePk, STATE_SORT_KEY))
                    .conditionExpression("#member = :member")
                    .expressionAttributeNames(Map.of("#member", MEMBER))
                    .expressionAttributeValues(Map.of(":member", stringValue(member)))
                    .build();
            actions.add(TransactWriteItem.builder().delete(dedupeDelete).build());
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(actions)
                    .build());
        } catch (TransactionCanceledException alreadyReleasedOrExpired) {
            // Release is idempotent; never decrement twice when a caller retries.
        }
    }

    private Optional<AuthTokenRecord> readAuthToken(String tokenHash, String tokenType) {
        String tokenPk = authTokenPk(tokenHash, tokenType);
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key(tokenPk, STATE_SORT_KEY))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> userId = stringAttribute(item, USER_ID);
        Optional<Long> expiry = longAttribute(item, EXPIRES_AT_MILLIS);
        if (userId.isEmpty() || expiry.isEmpty()
                || !tokenType.equals(stringAttribute(item, TOKEN_TYPE).orElse(null))
                || !tokenHash.equals(stringAttribute(item, TOKEN_HASH).orElse(null))) {
            return Optional.empty();
        }
        return Optional.of(new AuthTokenRecord(tokenPk, userId.get(), expiry.get()));
    }

    private Map<String, AttributeValue> expiringItem(
            String partitionKey,
            String sortKey,
            String itemKind,
            Instant expiresAt
    ) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PARTITION_KEY, stringValue(partitionKey));
        item.put(SORT_KEY, stringValue(sortKey));
        item.put(KIND, stringValue(itemKind));
        item.put(EXPIRES_AT, numberValue(ttlEpochSeconds(expiresAt)));
        item.put(EXPIRES_AT_MILLIS, numberValue(expiresAt.toEpochMilli()));
        return item;
    }

    private Map<String, AttributeValue> key(String partitionKey, String sortKey) {
        return Map.of(
                PARTITION_KEY, stringValue(partitionKey),
                SORT_KEY, stringValue(sortKey)
        );
    }

    private String authTokenPk(String tokenHash, String tokenType) {
        return namespacedKey("AUTH_TOKEN", tokenType + "\u0000" + tokenHash);
    }

    private String authUserPk(String userId) {
        return "EPHEMERAL#AUTH_USER#" + userId;
    }

    private String authIndexSk(String tokenHash, String tokenType) {
        return "TOKEN#" + sha256(tokenType + "\u0000" + tokenHash);
    }

    private String grantPk(String userId, String objectKey) {
        return namespacedKey("UPLOAD_GRANT", userId + "\u0000" + objectKey);
    }

    private String notificationReservationPk(String userId, String member) {
        return namespacedKey("NOTIFICATION_RESERVATION", userId + "\u0000" + member);
    }

    private String namespacedKey(String namespace, String value) {
        return "EPHEMERAL#" + namespace + "#" + sha256(value);
    }

    private AttributeValue stringValue(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue numberValue(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private Optional<String> stringAttribute(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.s() == null ? Optional.empty() : Optional.of(value.s());
    }

    private Optional<Long> longAttribute(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.n()));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> booleanAttribute(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.bool() == null
                ? Optional.empty()
                : Optional.of(value.bool());
    }

    private long ttlEpochSeconds(Instant expiresAt) {
        return expiresAt.getNano() == 0
                ? expiresAt.getEpochSecond()
                : Math.addExact(expiresAt.getEpochSecond(), 1L);
    }

    private long ceilDiv(long dividend, long divisor) {
        return Math.floorDiv(Math.addExact(dividend, divisor - 1L), divisor);
    }

    private void requireFutureExpiry(Instant expiresAt, Instant now) {
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Expiry must be in the future");
        }
    }

    private void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                hex.append(String.format("%02x", part));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private record AuthTokenRecord(String tokenPk, String userId, long expiresAtEpochMillis) {
    }
}
