package com.ironside.challengesphere.persistence.dynamodb.foundation;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Keyed access to the TTL-backed ChallengeSphere ephemeral table. */
public final class EphemeralTableGateway extends DynamoDbTableGateway {

    public EphemeralTableGateway(
            DynamoDbClient client,
            TableSchema<DynamoDbItem> itemSchema,
            String tableName
    ) {
        super(client, itemSchema, tableName);
    }
}
