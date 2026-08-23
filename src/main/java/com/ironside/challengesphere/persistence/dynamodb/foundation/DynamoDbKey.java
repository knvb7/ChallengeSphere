package com.ironside.challengesphere.persistence.dynamodb.foundation;

import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

/** A complete primary key for either ChallengeSphere DynamoDB table. */
public record DynamoDbKey(String partitionKey, String sortKey) {

    public DynamoDbKey {
        // Reuse the centralized validation and preserve the caller's value.
        DynamoDbKeys.immutableAttributeKey(partitionKey, sortKey);
    }

    public Map<String, AttributeValue> toAttributeMap() {
        return DynamoDbKeys.immutableAttributeKey(partitionKey, sortKey);
    }

    public Key toEnhancedKey() {
        return Key.builder()
                .partitionValue(partitionKey)
                .sortValue(sortKey)
                .build();
    }
}
