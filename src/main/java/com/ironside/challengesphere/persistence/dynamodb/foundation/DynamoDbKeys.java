package com.ironside.challengesphere.persistence.dynamodb.foundation;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Physical attribute names and helpers shared by every ChallengeSphere
 * DynamoDB access pattern.
 */
public final class DynamoDbKeys {

    public static final String PARTITION_KEY = "PK";
    public static final String SORT_KEY = "SK";
    public static final String ENTITY_TYPE = "entityType";
    public static final String PAYLOAD_JSON = "payloadJson";
    public static final String VERSION = "version";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_AT = "updatedAt";
    public static final String EXPIRES_AT = "expiresAt";

    public static final String GSI1_NAME = "GSI1";
    public static final String GSI2_NAME = "GSI2";
    public static final String GSI3_NAME = "GSI3";
    public static final String GSI4_NAME = "GSI4";
    public static final String GSI5_NAME = "GSI5";
    public static final String GSI6_NAME = "GSI6";

    public static final String GSI1_PARTITION_KEY = "GSI1PK";
    public static final String GSI1_SORT_KEY = "GSI1SK";
    public static final String GSI2_PARTITION_KEY = "GSI2PK";
    public static final String GSI2_SORT_KEY = "GSI2SK";
    public static final String GSI3_PARTITION_KEY = "GSI3PK";
    public static final String GSI3_SORT_KEY = "GSI3SK";
    public static final String GSI4_PARTITION_KEY = "GSI4PK";
    public static final String GSI4_SORT_KEY = "GSI4SK";
    public static final String GSI5_PARTITION_KEY = "GSI5PK";
    public static final String GSI5_SORT_KEY = "GSI5SK";
    public static final String GSI6_PARTITION_KEY = "GSI6PK";
    public static final String GSI6_SORT_KEY = "GSI6SK";

    private static final String COMPONENT_SEPARATOR = "#";

    private DynamoDbKeys() {
    }

    /**
     * Joins already-normalized key components using the table-wide delimiter.
     * Components are deliberately not case-folded because identifiers and
     * pre-normalized search values have different case semantics.
     */
    public static String join(String first, String... remaining) {
        StringBuilder key = new StringBuilder(requireComponent(first, "first"));
        for (int index = 0; index < remaining.length; index++) {
            key.append(COMPONENT_SEPARATOR)
                    .append(requireComponent(remaining[index], "remaining[" + index + "]"));
        }
        return key.toString();
    }

    public static String prefix(String entityType) {
        return requireComponent(entityType, "entityType") + COMPONENT_SEPARATOR;
    }

    public static DynamoDbKey key(String partitionKey, String sortKey) {
        return new DynamoDbKey(partitionKey, sortKey);
    }

    public static Map<String, AttributeValue> attributeKey(String partitionKey, String sortKey) {
        return key(partitionKey, sortKey).toAttributeMap();
    }

    public static KeysAndAttributes keysAndAttributes(
            List<DynamoDbKey> keys,
            boolean consistentRead
    ) {
        Objects.requireNonNull(keys, "keys");
        return KeysAndAttributes.builder()
                .keys(keys.stream().map(DynamoDbKey::toAttributeMap).toList())
                .consistentRead(consistentRead)
                .build();
    }

    static Map<String, AttributeValue> immutableAttributeKey(String partitionKey, String sortKey) {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>(2);
        attributes.put(PARTITION_KEY, AttributeValue.fromS(requireComponent(partitionKey, PARTITION_KEY)));
        attributes.put(SORT_KEY, AttributeValue.fromS(requireComponent(sortKey, SORT_KEY)));
        return Map.copyOf(attributes);
    }

    private static String requireComponent(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must not contain a NUL character");
        }
        return value;
    }
}
