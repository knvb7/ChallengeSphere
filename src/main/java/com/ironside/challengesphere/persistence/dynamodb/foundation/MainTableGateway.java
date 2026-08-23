package com.ironside.challengesphere.persistence.dynamodb.foundation;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Keyed access to the durable ChallengeSphere table. */
public final class MainTableGateway extends DynamoDbTableGateway {

    public MainTableGateway(
            DynamoDbClient client,
            TableSchema<DynamoDbItem> itemSchema,
            String tableName
    ) {
        super(client, itemSchema, tableName);
    }
}
