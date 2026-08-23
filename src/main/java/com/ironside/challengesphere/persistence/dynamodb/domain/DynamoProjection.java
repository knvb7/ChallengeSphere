package com.ironside.challengesphere.persistence.dynamodb.domain;

import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;

import java.util.Objects;

/**
 * A materialized access-path copy of a canonical aggregate.
 *
 * <p>Projection items deliberately use the table primary key instead of a
 * filter or scan. This keeps every request-path lookup expressible as a
 * DynamoDB {@code Query}.</p>
 */
public record DynamoProjection(String partitionKey, String sortKey) {

    public DynamoProjection {
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
        if (sortKey == null || sortKey.isBlank()) {
            throw new IllegalArgumentException("sortKey must not be blank");
        }
    }

    public DynamoDbKey key() {
        return new DynamoDbKey(partitionKey, sortKey);
    }

    public static DynamoProjection of(String partitionKey, String sortKey) {
        return new DynamoProjection(
                Objects.requireNonNull(partitionKey, "partitionKey"),
                Objects.requireNonNull(sortKey, "sortKey")
        );
    }
}
