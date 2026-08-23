package com.ironside.challengesphere.persistence.dynamodb.domain;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

/** A bounded key-query page plus the opaque DynamoDB continuation state. */
public record DynamoPage<T>(
        List<T> items,
        Map<String, AttributeValue> lastEvaluatedKey
) {
    public DynamoPage {
        items = items == null ? List.of() : List.copyOf(items);
        lastEvaluatedKey = lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()
                ? Map.of()
                : Map.copyOf(lastEvaluatedKey);
    }

    public boolean hasMore() {
        return !lastEvaluatedKey.isEmpty();
    }
}
